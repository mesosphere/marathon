package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v2.{AppUpdate, Group}
import mesosphere.marathon.state.{Timestamp, MarathonState}
import mesosphere.marathon.Protos.DeploymentPlanDefinition
import scala.concurrent.{Promise, Future}
import mesosphere.marathon.{NoRollbackNeeded, MarathonSchedulerService}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import org.apache.log4j.Logger

case class DeploymentPlan(
  id: String,
  original: Group,
  target: Group,
  version : Timestamp = Timestamp.now()
) extends MarathonState[DeploymentPlanDefinition, DeploymentPlan] {

  private[this] val log = Logger.getLogger(getClass.getName)

  def originalIds : Set[String] = original.apps.map(_.id).toSet

  def targetIds : Set[String] = target.apps.map(_.id).toSet

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan = mergeFromProto(DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: DeploymentPlanDefinition): DeploymentPlan = DeploymentPlan(
    msg.getId,
    Group.empty().mergeFromProto(msg.getOriginial),
    Group.empty().mergeFromProto(msg.getTarget),
    Timestamp(msg.getVersion)
  )

  override def toProto: DeploymentPlanDefinition = {
    DeploymentPlanDefinition.newBuilder()
      .setId(id)
      .setVersion(version.toString)
      .setOriginial(original.toProto)
      .setTarget(target.toProto)
      .build()
  }

  lazy val (toStart, toStop, toScale, toRestart) = {
    val isUpdate = targetIds.intersect(originalIds)
    val origTarget = isUpdate.flatMap(id => original.apps.find(_.id==id)).zip(isUpdate.flatMap(id => target.apps.find(_.id==id)))
    ( targetIds.filterNot(isUpdate.contains).flatMap(id => target.apps.find(_.id==id)),
      originalIds.filterNot(isUpdate.contains).flatMap(id => original.apps.find(_.id==id)),
      origTarget.filter{case (from, to) => from.isOnlyScaleChange(to)}.map(_._2),
      origTarget.filter { case (from, to) => from.isUpgrade(to) }.map(_._2))
  }

  def deploy(scheduler: MarathonSchedulerService, rollbackOnFailure: Boolean = true, isRollback: Boolean = false): Future[Boolean] = {
    val locks = if (isRollback) Nil else targetIds.map(scheduler.appLocks(_))
    locks.foreach(_.acquire())

    log.info(s"Deploy group ${target.id}: start:${toStart.map(_.id)}, stop:${toStop.map(_.id)}, scale:${toScale.map(_.id)}, restart:${toRestart.map(_.id)}")
    val updateFuture = toScale.map(to => scheduler.updateApp(to.id, AppUpdate(instances = Some(to.instances))).map(_ => true))
    val restartFuture = toRestart.map(app => scheduler.upgradeApp(app, (app.instances * target.scalingStrategy.minimumHealthCapacity).toInt))
    val startFuture = toStart.map(scheduler.startApp(_).map(_ => true))
    val stopFuture = toStop.map(scheduler.stopApp(_).map(_ => true))
    val deployFuture = Future.sequence(startFuture ++ updateFuture ++ restartFuture ++ stopFuture).map(_.forall(identity))
    val result = if (rollbackOnFailure) rollback(deployFuture, scheduler) else deployFuture

    result andThen { case _ => locks.foreach(_.release()) }
  }

  def rollbackPlan : DeploymentPlan = DeploymentPlan(
    id = id,
    original = target,
    target = original.copy(
      apps = original.apps.map(_.copy(version = Timestamp.now()))
    )
  )

  private def rollback(result:Future[Boolean], scheduler: MarathonSchedulerService) : Future[Boolean] = {
    val promise = Promise[Boolean]()
    result.onComplete {
      case Success(true) => promise.complete(Success(true))
      case Failure(ex) if ex.isInstanceOf[NoRollbackNeeded] => promise.complete(Success(false))
      case failure =>
        log.info(s"Deployment of group ${target.id} failed! Do a rollback")
        rollbackPlan.deploy(scheduler, rollbackOnFailure = false, isRollback = true).map(ignore => promise.complete(failure))
    }
    promise.future
  }
}

object DeploymentPlan {
  def empty() = DeploymentPlan("", Group.empty(), Group.empty())
}
