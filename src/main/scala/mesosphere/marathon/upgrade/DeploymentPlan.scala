package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.{AppUpdate, ScalingStrategy, Group}
import mesosphere.marathon.state.{Timestamp, MarathonState}
import mesosphere.marathon.Protos.DeploymentPlanDefinition
import scala.collection.JavaConversions._
import scala.concurrent.{Promise, Future}
import mesosphere.marathon.MarathonSchedulerService
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

case class DeploymentPlan(
  id: String,
  strategy: ScalingStrategy,
  original: List[AppDefinition],
  target: List[AppDefinition],
  version : Timestamp = Timestamp.now()
) extends MarathonState[DeploymentPlanDefinition, DeploymentPlan] {

  def originalIds : Set[String] = original.map(_.id).toSet

  def targetIds : Set[String] = target.map(_.id).toSet

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan = mergeFromProto(DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: DeploymentPlanDefinition): DeploymentPlan = DeploymentPlan(
    msg.getId,
    ScalingStrategy.fromProto(msg.getStrategy),
    msg.getOrigList.map(AppDefinition.fromProto).toList,
    msg.getTargetList.map(AppDefinition.fromProto).toList,
    Timestamp(msg.getVersion)
  )

  override def toProto: DeploymentPlanDefinition = {
    val builder = DeploymentPlanDefinition.newBuilder()
      .setId(id)
      .setVersion(version.toString)
      .setStrategy(strategy.toProto)
      .addAllOrig(original.map(_.toProto))
      .addAllTarget(target.map(_.toProto))
    builder.build()
  }

  def deploy(scheduler: MarathonSchedulerService, rollbackOnFailure:Boolean=true): Future[Boolean] = {
    val isUpdate = targetIds.intersect(originalIds)
    val toStart = targetIds.filterNot(isUpdate.contains)
    val toStop = originalIds.filterNot(isUpdate.contains)
    val origTarget = isUpdate.flatMap(id => original.find(_.id==id)).zip(isUpdate.flatMap(id => target.find(_.id==id)))
    val updateFuture = origTarget
      .filter{case (from, to) => from.isOnlyScaleChange(to)}
      .map{ case (_, to) => scheduler.updateApp(to.id, AppUpdate(instances = Some(to.instances))).map(_ => true)}
    val restartFuture = origTarget
      .filter { case (from, to) => from.isUpgrade(to) }
      .map { case (_, app) => scheduler.upgradeApp(app, (app.instances * strategy.minimumHealthCapacity).toInt)}
    val startFuture = toStart.flatMap(id => target.find(_.id==id)).map(scheduler.startApp(_).map(_ => true))
    val stopFuture = toStop.flatMap(id => original.find(_.id==id)).map(scheduler.stopApp(_).map(_ => true))
    val result = Future.sequence(startFuture ++ updateFuture ++ restartFuture ++ stopFuture).map(_.forall(identity))
    if (rollbackOnFailure) rollback(result, scheduler) else result
  }

  private def rollback(result:Future[Boolean], scheduler: MarathonSchedulerService) : Future[Boolean] = {
    val promise = Promise[Boolean]()
    result.onComplete {
      case success@Success(true) => promise.complete(success)
      case failure => DeploymentPlan(id, strategy, target, original)
                      .deploy(scheduler, false).map(ignore => promise.complete(failure))
    }
    promise.future
  }
}

object DeploymentPlan {
  def apply(id:String, oldProduct:Group, newProduct:Group, runningTasks:Map[String, List[String]]) : DeploymentPlan = {
    DeploymentPlan(id, newProduct.scalingStrategy, oldProduct.apps.toList, newProduct.apps.toList)
  }

  def empty() = DeploymentPlan("", ScalingStrategy(0), Nil, Nil)
}
