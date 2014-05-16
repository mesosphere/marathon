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
    (targetIds.filterNot(isUpdate.contains),
      originalIds.filterNot(isUpdate.contains),
      origTarget.filter{case (from, to) => from.isOnlyScaleChange(to)},
      origTarget.filter { case (from, to) => from.isUpgrade(to) })
  }

  def deploy(scheduler: MarathonSchedulerService, rollbackOnFailure: Boolean = true, isRollback: Boolean = false): Future[Boolean] = {
    val locks = if (isRollback) Nil else targetIds.map(scheduler.appLocks(_))
    locks.foreach(_.acquire())
    val updateFuture = toScale.map{ case (_, to) => scheduler.updateApp(to.id, AppUpdate(instances = Some(to.instances))).map(_ => true)}
    val restartFuture = toRestart.map { case (_, app) => scheduler.upgradeApp(app, (app.instances * target.scalingStrategy.minimumHealthCapacity).toInt)}
    val startFuture = toStart.flatMap(id => target.apps.find(_.id==id)).map(scheduler.startApp(_).map(_ => true))
    val stopFuture = toStop.flatMap(id => original.apps.find(_.id==id)).map(scheduler.stopApp(_).map(_ => true))
    val result = Future.sequence(startFuture ++ updateFuture ++ restartFuture ++ stopFuture).map(_.forall(identity))
    val res = if (rollbackOnFailure) rollback(result, scheduler) else result

    res andThen { case _ =>
      locks.foreach(_.release())
    }
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
