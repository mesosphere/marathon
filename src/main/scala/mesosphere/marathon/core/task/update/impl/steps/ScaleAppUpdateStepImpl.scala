package mesosphere.marathon.core.task.update.impl.steps
//scalastyle:off
import javax.inject.Named

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.concurrent.Future
//scalastyle:on
/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
  @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef])
    extends TaskUpdateStep with InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): we might want to consider scaling pods in case of status Reserved or Unreachable, which are both
    // not Terminal. That should be up to a tbd InstanceLostBehavior.-
    update.status match {
      case _: InstanceStatus.Terminal =>
        val runSpecId = update.runSpecId
        val instanceId = update.id
        val state = update.status
        log.info(s"initiating a scale check for runSpec [$runSpecId] due to [$instanceId] $state")
        // TODO(PODS):
        //    1) SchedulerActor is awful
        //    2) yet it handles runSpecs locked due to deployments
        //    3) if we still use it, we should rename the Message and make the SchedulerActor generic
        schedulerActor ! ScaleApp(runSpecId)

      case _ =>
      // nothing
    }

    Future.successful(Done)
  }

  // TODO(PODS): remove this function
  override def processUpdate(taskChanged: TaskChanged): Future[_] = {

    val terminalOrExpungedInstance: Option[Instance] = {
      (taskChanged.stateOp, taskChanged.stateChange) match {
        // stateOp is a terminal MesosUpdate
        case (InstanceUpdateOperation.MesosUpdate(instance, _: InstanceStatus.Terminal, mesosStatus, _), _) =>
          Some(instance)

        // A Lost task was is being expunged
        case (InstanceUpdateOperation.MesosUpdate(instance, InstanceStatus.Unreachable, mesosState, _),
          InstanceUpdateEffect.Expunge(task)) => Some(instance)

        // A Lost task that might come back and is not expunged but updated
        case (InstanceUpdateOperation.MesosUpdate(instance, InstanceStatus.Unreachable, mesosState, _),
          InstanceUpdateEffect.Update(task, _)) => Some(instance)

        // stateChange is an expunge (probably because we expunged a timeout reservation)
        case (_, InstanceUpdateEffect.Expunge(task)) =>
          // TODO(PODS): TaskStateChange -> InstanceStateChange
          // Some(instance)
          ???

        // no ScaleApp needed
        case _ => None
      }
    }

    terminalOrExpungedInstance.foreach { instance =>
      val runSpecId = instance.runSpecId
      val instanceId = instance.instanceId
      // logging is accordingly old mesos.Protos.TaskState representation
      val state = instance.state.status.toMesosStateName
      log.info(s"initiating a scale check for runSpecId [$runSpecId] due to [$instanceId] $state")
      log.info("schedulerActor: {}", schedulerActor)
      schedulerActor ! ScaleApp(runSpecId)
    }

    Future.successful(())
  }
}
