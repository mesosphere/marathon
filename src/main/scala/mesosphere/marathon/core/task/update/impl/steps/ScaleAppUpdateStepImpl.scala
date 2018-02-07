package mesosphere.marathon
package core.task.update.impl.steps
//scalastyle:off
import javax.inject.Named

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import mesosphere.marathon.storage.repository.GroupRepository
import org.slf4j.LoggerFactory

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.DurationInt
//scalastyle:on
/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef],
    groupRepositoryProvider: Provider[GroupRepository]) extends InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()
  private[this] lazy val groupRepository = groupRepositoryProvider.get()

  private[this] def scalingWorthy: Condition => Boolean = {
    case Condition.Reserved | Condition.UnreachableInactive | _: Condition.Terminal => true
    case _ => false
  }

  override def name: String = "scaleApp"

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): it should be up to a tbd TaskUnreachableBehavior how to handle Unreachable
    calcScaleEvent(update).foreach(event => schedulerActor ! event)
    Future.successful(Done)
  }

  def calcScaleEvent(update: InstanceChange): Option[ScaleRunSpec] = {
    val group = Await.result(groupRepository.root(), 100.millis)
    val isOneTime = group.runSpec(update.runSpecId).exists(_.oneTime)
    if (!isOneTime && scalingWorthy(update.condition) && update.lastState.forall(lastState => !scalingWorthy(lastState.condition))) {
      val runSpecId = update.runSpecId
      val instanceId = update.id
      val state = update.condition
      log.info(s"initiating a scale check for runSpec [$runSpecId] due to [$instanceId] $state")
      // TODO(PODS): we should rename the Message and make the SchedulerActor generic
      Some(ScaleRunSpec(runSpecId))
    } else {
      None
    }
  }
}
