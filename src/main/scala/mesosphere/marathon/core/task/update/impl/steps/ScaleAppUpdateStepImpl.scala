package mesosphere.marathon
package core.task.update.impl.steps

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{Inject, Provider}
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Named
import mesosphere.marathon.MarathonSchedulerActor.{DecommissionInstance, ScaleRunSpec, StartInstance}
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