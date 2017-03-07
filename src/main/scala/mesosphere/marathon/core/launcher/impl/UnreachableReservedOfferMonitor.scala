package mesosphere.marathon
package core.launcher.impl

import akka.event.Logging
import akka.NotUsed
import akka.stream.{ Attributes, Materializer }
import mesosphere.marathon.stream.Implicits._
import scala.collection.breakOut
import scala.concurrent.Future

import akka.stream.{ ActorAttributes, OverflowStrategy, Supervision }
import akka.stream.scaladsl.{ Flow, Sink, Source, SourceQueueWithComplete }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ Offer, SlaveID, TaskState, TaskStatus }

/**
  * Until Mesos 1.3.0, resident tasks have no path to become reachable / terminal (see GitHub issue #5284). This module
  * is our workaround.
  *
  * We provide a graph which receives offers and watches for reservations for unreachable tasks, upon the sight of which
  * we emit a TASK_GONE status for said unreachable task.
  *
  * We expect the stream (and therefore, the input buffer) to be idle / empty most of the time, and the lookup / publish
  * operations to be cheap / near instantaneous. However, we restrict the concurrency because if, by chance, the system
  * is under heavy load, we would prefer to drop offers and not overload the lookup / task status publish system so that
  * other work-loads can finish. Mesos will eventually re-offer the reservation; dropping is harmless.
  *
  * [Offer] ~> [Reserved TaskIDs (for our framework id)] ~> [Looked up instances] ~> [unreachable instances] ~>
  *   [TASK_GONE status] ~> publish (to a TaskStatusUpdateProcessor implementation's publish method)
  *
  * Errors are logged, dropping the error-inducing element and the stream resumes processing.
  */
object UnreachableReservedOfferMonitor extends StrictLogging {
  /**
    * Too low a value reduces scheduling efficiency
    * Too high a value places more demand on the instance lookup and task status publisher systems.
    */
  private val Parallelism = 16

  /**
    * We buffer up to 32 offers in the event of back pressure. We should rarely hit this. If we do, then dropping offers
    * is the right thing to do
    */
  private val BufferSize = 32

  /** Given an offer, emit all Instance Ids pertaining to this Marathon instance that have reservations in the offer */
  private[impl] val reservedInstanceIdsForReservations: Flow[Offer, Instance.Id, NotUsed] =
    Flow[Offer]
      .mapConcat { offer =>
        // This doesn't change during the lifetime of Marathon but it's simpler to get it from here.
        val frameworkId = FrameworkId(offer.getFrameworkId.getValue)
        offer.getResourcesList.iterator
          .flatMap(TaskLabels.taskIdForResource(frameworkId, _))
          .map(_.instanceId)
          .to[Seq]
      }
      .named("reservedInstanceIdsForReservations")

  /** Looks up instances using the provided lookup function. Drops on None */
  private[impl] def lookupInstanceFlow(
    lookupInstance: Instance.Id => Future[Option[Instance]]) =
    Flow[Instance.Id]
      .mapAsync(parallelism = Parallelism)(lookupInstance)
      .collect { case Some(instance) => instance }
      .named("lookupInstance")

  private def makeGoneStatus(taskId: Task.Id, slaveId: String): TaskStatus = {
    TaskStatus.newBuilder
      .setState(TaskState.TASK_GONE)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(SlaveID.newBuilder.setValue(slaveId))
      .setMessage("Reserved resources were offered")
      .build()
  }

  /** Given a source of Instance, yield TASK_GONE mesos updates for every unreachable instance */
  private[impl] val unreachableToMesosGoneUpdates =
    Flow[Instance]
      .filter(_.isUnreachable)
      .mapConcat { instance =>
        logger.info(s"Offer received for unreachable reserved instance ${instance.instanceId}")
        instance.agentInfo.agentId match {
          case Some(agentIdString) =>
            instance.tasksMap.values.map { task =>
              makeGoneStatus(task.taskId, agentIdString)
            }(breakOut)
          case None =>
            Nil
        }
      }
      .named("unreachableToMesosGoneUpdates")

  private[impl] val attributes =
    ActorAttributes.supervisionStrategy { exception =>
      logger.error("Exception in offer monitor stream", exception)
      Supervision.Resume
    }.and(
      Attributes.logLevels(
        onElement = Logging.InfoLevel,
        onFinish = Logging.InfoLevel,
        onFailure = Logging.ErrorLevel))

  /** ~> [Offer] ~> [Lookup instance] ~> [TaskStatus] */
  private[impl] def monitorFlow(
    lookupInstance: Instance.Id => Future[Option[Instance]]
  ): Flow[Offer, TaskStatus, NotUsed] =
    Flow[Offer]
      .via(reservedInstanceIdsForReservations)
      .via(lookupInstanceFlow(lookupInstance))
      .via(unreachableToMesosGoneUpdates)
      .log("Reservation seen for unreachable instance; Generating TASK_GONE update", identity)
      .named("monitorFlow")

  /**
    * See [[UnreachableReservedOfferMonitor$]]
    *
    * @param lookupInstance - function used to lookup an instance
    * @param taskStatusPublisher - function called with each resulting TASK_GONE update
    */
  def run(
    lookupInstance: Instance.Id => Future[Option[Instance]],
    taskStatusPublisher: TaskStatus => Future[Unit]
  )(implicit m: Materializer): SourceQueueWithComplete[Offer] = {
    Source.queue[Offer](
      bufferSize = BufferSize, overflowStrategy = OverflowStrategy.dropNew)
      .via(monitorFlow(lookupInstance))
      // resolving the future will cause any errors to be logged
      .mapAsyncUnordered(parallelism = Parallelism)(taskStatusPublisher)
      .to(Sink.ignore) // just a bunch of Units at this point
      .withAttributes(attributes)
      .run
  }
}
