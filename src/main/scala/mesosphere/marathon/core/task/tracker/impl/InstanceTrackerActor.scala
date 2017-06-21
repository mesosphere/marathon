package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceDeleted, InstanceUpdateEffect, InstanceUpdateOperation, InstanceUpdated }
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerUpdateStepProcessor }
import mesosphere.marathon.metrics.AtomicGauge
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

object InstanceTrackerActor {
  def props(
    metrics: ActorMetrics,
    taskLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    taskUpdaterProps: ActorRef => Props): Props = {
    Props(new InstanceTrackerActor(metrics, taskLoader, updateStepProcessor, taskUpdaterProps))
  }

  /** Query the current [[InstanceTracker.SpecInstances]] from the [[InstanceTrackerActor]]. */
  private[impl] case object List

  private[impl] case class Get(taskId: Instance.Id)

  /** Forward an update operation to the child [[InstanceUpdateActor]]. */
  private[impl] case class ForwardTaskOp(deadline: Timestamp, instanceId: Instance.Id, op: InstanceUpdateOperation)

  /** Describes where and what to send after an update event has been processed by the [[InstanceTrackerActor]]. */
  private[impl] case class Ack(initiator: ActorRef, effect: InstanceUpdateEffect) extends StrictLogging {
    def sendAck(): Unit = {
      val msg = effect match {
        case InstanceUpdateEffect.Failure(cause) => Status.Failure(cause)
        case _ => effect
      }
      logger.debug(s"Send acknowledgement: initiator=$initiator msg=$msg")
      initiator ! msg
    }
  }

  /** Inform the [[InstanceTrackerActor]] of a task state change (after persistence). */
  private[impl] case class StateChanged(ack: Ack)

  private[tracker] class ActorMetrics {
    // We can't use Metrics as we need custom names for compatibility.
    val stagedCount: AtomicGauge = AtomicGauge("service.mesosphere.marathon.task.staged.count")
    val runningCount: AtomicGauge = AtomicGauge("service.mesosphere.marathon.task.running.count")

    def resetMetrics(): Unit = {
      stagedCount.setValue(0)
      runningCount.setValue(0)
    }
  }
}

/**
  * Holds the current in-memory version of all task state. It gets informed of task state changes
  * after they have been persisted.
  *
  * It also spawns the [[InstanceUpdateActor]] as a child and forwards update operations to it.
  */
private[impl] class InstanceTrackerActor(
    metrics: InstanceTrackerActor.ActorMetrics,
    taskLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    taskUpdaterProps: ActorRef => Props) extends Actor with Stash {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val updaterRef = context.actorOf(taskUpdaterProps(self), "updater")

  override val supervisorStrategy = OneForOneStrategy() { case _: Exception => Escalate }

  override def preStart(): Unit = {
    super.preStart()

    log.info(s"${getClass.getSimpleName} is starting. Task loading initiated.")
    metrics.resetMetrics()

    import akka.pattern.pipe
    import context.dispatcher
    taskLoader.load().pipeTo(self)
  }

  override def postStop(): Unit = {
    metrics.resetMetrics()

    super.postStop()
  }

  override def receive: Receive = initializing

  private[this] def initializing: Receive = LoggingReceive.withLabel("initializing") {
    case appTasks: InstanceTracker.InstancesBySpec =>
      log.info("Task loading complete.")

      unstashAll()
      context.become(withTasks(
        appTasks,
        TaskCounts(appTasks.allInstances, healthStatuses = Map.empty)))

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  private[this] def withTasks(instancesBySpec: InstanceTracker.InstancesBySpec, counts: TaskCounts): Receive = {

    def becomeWithUpdatedApp(appId: PathId)(instanceId: Instance.Id, newInstance: Option[Instance]): Unit = {
      val updatedAppTasks = newInstance match {
        case None => instancesBySpec.updateApp(appId)(_.withoutInstance(instanceId))
        case Some(instance) => instancesBySpec.updateApp(appId)(_.withInstance(instance))
      }

      val updatedCounts = {
        val oldInstance = instancesBySpec.instance(instanceId)
        // we do ignore health counts
        val oldTaskCount = TaskCounts(oldInstance.to[Seq], healthStatuses = Map.empty)
        val newTaskCount = TaskCounts(newInstance.to[Seq], healthStatuses = Map.empty)
        counts + newTaskCount - oldTaskCount
      }

      context.become(withTasks(updatedAppTasks, updatedCounts))
    }

    // this is run on any state change
    metrics.stagedCount.setValue(counts.tasksStaged.toLong)
    metrics.runningCount.setValue(counts.tasksRunning.toLong)

    LoggingReceive.withLabel("withTasks") {
      case InstanceTrackerActor.List =>
        sender() ! instancesBySpec

      case InstanceTrackerActor.Get(taskId) =>
        sender() ! instancesBySpec.instance(taskId)

      case ForwardTaskOp(deadline, taskId, taskStateOp) =>
        val op = InstanceOpProcessor.Operation(deadline, sender(), taskId, taskStateOp)
        updaterRef.forward(InstanceUpdateActor.ProcessInstanceOp(op))

      case msg @ InstanceTrackerActor.StateChanged(ack) =>
        val maybeChange: Option[InstanceChange] = ack.effect match {
          case InstanceUpdateEffect.Update(instance, oldState, events) =>
            becomeWithUpdatedApp(instance.runSpecId)(instance.instanceId, newInstance = Some(instance))
            Some(InstanceUpdated(instance, lastState = oldState.map(_.state), events))

          case InstanceUpdateEffect.Expunge(instance, events) =>
            becomeWithUpdatedApp(instance.runSpecId)(instance.instanceId, newInstance = None)
            Some(InstanceDeleted(instance, lastState = None, events))

          case InstanceUpdateEffect.Noop(_) |
            InstanceUpdateEffect.Failure(_) =>
            None
        }

        val originalSender = sender()

        import context.dispatcher
        maybeChange.map { change =>
          updateStepProcessor.process(change).recover {
            case NonFatal(cause) =>
              // since we currently only use ContinueOnErrorSteps, we can simply ignore failures here
              log.warn("updateStepProcessor.process failed", cause)
              Done
          }
        }.getOrElse(Future.successful(Done)).foreach { _ =>
          ack.sendAck()
          originalSender ! (())
        }
    }
  }
}
