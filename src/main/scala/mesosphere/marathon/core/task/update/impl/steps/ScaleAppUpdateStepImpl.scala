package mesosphere.marathon
package core.task.update.impl.steps

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{Inject, Provider}
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Named
import mesosphere.marathon.MarathonSchedulerActor.{DecommissionInstance, StartInstance}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}

import scala.concurrent.Future

/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends InstanceChangeHandler with StrictLogging {

  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  /**
    * An instance becomes [[Condition.UnreachableInactive]] the first time it transitions from any other condition
    * into this one.
    *
    * @return true if the instance became UnreachableInactive or false otherwise
    */
  private[this] def becameUnreachableInactive(update: InstanceChange): Boolean = {
    update.lastState.exists { last =>
      last.condition != Condition.UnreachableInactive && update.condition == Condition.UnreachableInactive
    }
  }

  /**
    * An instance becomes reachable again the first time it transitions from [[Condition.UnreachableInactive]] into any
    * [[Condition.Active]] or [[Condition.Terminal]] (can it even happen?) condition.
    *
    * @param update
    * @return true if the instance became reachable again or false otherwise
    */
  private[this] def becameReachableAgain(update: InstanceChange): Boolean = {
    update.lastState.exists { last =>
      last.condition == Condition.UnreachableInactive && (update.condition.isActive || update.condition.isTerminal)
    }
  }

  override def name: String = "scaleApp"
  override def metricName: String = "scale-app"

  /**
    * This step only reacts to an Instance transitioning to and from [[Condition.UnreachableInactive]].
    * - when becoming UnreachableInactive a replacement instance has to be launched
    * - should an instance become reachable again, an existing one might have to be decommissioned
    *
    * However the act of launching new instance/decommissioning an old one can not happen here - there might
    * exist an ongoing deployment and in this case a deployment actor will take care of that. The only actor that
    * has this knowledge is the [[MarathonSchedulerActor]] - it holds the "locks" for all runSpec Ids that are
    * currently in a deployment. This is why we only send a command to it here.
    *
    * In case you're wondering, why this step is needed at all - why not react to the transition directly in
    * the [[MarathonSchedulerActor]]? This is because the only event available "outside" of these steps is
    * [[mesosphere.marathon.core.event.InstanceChanged]] events and it doesn't have the previous instance state which
    * is needed to make the decision (see [[becameReachableAgain]] and [[becameUnreachableInactive]] methods).
    */
  override def process(update: InstanceChange): Future[Done] = {
    maybeSchedulerCommand(update).foreach(event => schedulerActor ! event)
    Future.successful(Done)
  }

  def maybeSchedulerCommand(update: InstanceChange): Option[MarathonSchedulerActor.Command] = {
    if (becameUnreachableInactive(update)) {
      logger.info(s">>> Instance ${update.id} became UnreachableInactive. Will try to start a new instance.")
      Some(StartInstance(update.instance.runSpec))
    } else if (becameReachableAgain(update)) {
      logger.info(s">>> Instance ${update.id} became reachable again. Will try to decommission an existing one.")
      Some(DecommissionInstance(update.instance.runSpec))
    } else {
      logger.info(s">>> Instance ${update.id} has been updated. Nothing to do here")
      None
    }
  }
}