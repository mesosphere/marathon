package mesosphere.marathon
package core.task.termination.impl

import akka.NotUsed
import akka.actor.Cancellable
import akka.Done
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Flow, Source }
import com.typesafe.scalalogging.StrictLogging
import java.util.UUID
import mesosphere.marathon.core.event.{ InstanceChanged, UnknownInstanceTerminated }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.stream.{ EnrichedFlow, EnrichedSource }

object KillStreamWatcher extends StrictLogging {

  /**
    * Monitor specified event stream for termination events, and yield instanceIds.
    *
    * Can be cancelled via materialize Cancellable.
    *
    * See [[mesosphere.marathon.stream.EnrichedSource$.eventBusSource]]
    */
  private def killedInstanceIds(eventStream: akka.event.EventStream): Source[Instance.Id, Cancellable] = {
    // MaxValue causes a dynamically sized buffer to be used.
    val bufferSize = Int.MaxValue
    val overflowStrategy = OverflowStrategy.fail
    val killedViaInstanceChanged =
      EnrichedSource.eventBusSource(classOf[InstanceChanged], eventStream, bufferSize, overflowStrategy).collect {
        case event if considerTerminal(event.condition) => event.id
      }

    val killedViaUnknownInstanceTerminated =
      EnrichedSource.eventBusSource(
        classOf[UnknownInstanceTerminated], eventStream, bufferSize, overflowStrategy).map(_.id)

    // eagerComplete allows us to toss the right materialized cancellable value
    killedViaInstanceChanged.
      merge(killedViaUnknownInstanceTerminated, eagerComplete = true)
  }

  private val singleDone = List(Done)
  private[impl] def killedInstanceFlow(instanceIds: Iterable[Instance.Id]): Flow[Instance.Id, Done, NotUsed] = {

    val instanceIdsSet = instanceIds.toSet
    if (instanceIdsSet.isEmpty) {
      logger.info("Asked to watch no instances. Completing immediately.")
      EnrichedFlow.ignore[Instance.Id].prepend(Source.single(Done)).take(1)
    } else {
      Flow[Instance.Id].
        filter(instanceIdsSet).
        statefulMapConcat { () =>
          var pendingInstanceIds = instanceIdsSet
          // this is used for logging to help prevent polluting the logs
          val name = s"kill-watcher-${UUID.randomUUID()}"

          { (id: Instance.Id) =>
            pendingInstanceIds -= id
            logger.debug(s"Received terminal update for ${id}")
            if (pendingInstanceIds.isEmpty) {
              logger.info(s"${name} done watching; all watched instances were killed")
              singleDone
            } else {
              logger.info(s"${name} still waiting for ${pendingInstanceIds.size} instances to be killed")
              Nil
            }
          }
        }.
        take(1)
    }
  }

  /**
    * This Source definition watches the specified event bus and yields a single Done (and completes) when all of the
    * specified instanceIds have been reported terminal (including LOST et al). The event bus subscription is registered
    * during stream materialization.
    *
    * It does not automatically timeout. It can be cancelled via the returned Cancellable.
    *
    * Completes immediately if instanceIds are empty.
    *
    * @param eventStream the eventStream to be monitored for kill events
    * @param instanceIds the instanceIds that shall be watched.
    */
  def watchForKilledInstances(
    eventStream: akka.event.EventStream, instanceIds: Iterable[Instance.Id]): Source[Done, Cancellable] = {

    killedInstanceIds(eventStream).
      via(killedInstanceFlow(instanceIds))
  }
}
