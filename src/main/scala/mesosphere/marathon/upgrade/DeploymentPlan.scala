package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{ PathId, Group, Timestamp, MarathonState }
import mesosphere.marathon.Protos.DeploymentPlanDefinition
import mesosphere.marathon.state.Migration
import mesosphere.marathon.Protos.StorageVersion
import scala.concurrent.Future
import mesosphere.marathon.MarathonSchedulerService
import org.apache.log4j.Logger

sealed trait DeploymentAction
//application has not been started before
final case class StartApplication(app: AppDefinition, scaleTo: Int) extends DeploymentAction
//application is started, but more instances should be started
final case class ScaleApplication(app: AppDefinition, scaleTo: Int) extends DeploymentAction
//application is started, but shall be completely stopped
final case class StopApplication(app: AppDefinition) extends DeploymentAction
//application is restarted, but there are still instances of the old application
final case class KillAllOldTasksOf(app: AppDefinition) extends DeploymentAction
//application is there but should be replaced
final case class RestartApplication(app: AppDefinition, scaleOldTo: Int, scaleNewTo: Int) extends DeploymentAction

final case class DeploymentStep(actions: List[DeploymentAction]) {
  def +(step: DeploymentStep) = DeploymentStep(actions ++ step.actions)
}

final case class DeploymentPlan(
    id: String,
    original: Group,
    target: Group,
    steps: List[DeploymentStep],
    version: Timestamp) extends MarathonState[DeploymentPlanDefinition, DeploymentPlan] {

  private[this] val log = Logger.getLogger(getClass.getName)

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan = mergeFromProto(DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: DeploymentPlanDefinition): DeploymentPlan = DeploymentPlan(
    msg.getId,
    Group.empty.mergeFromProto(msg.getOriginial),
    Group.empty.mergeFromProto(msg.getTarget),
    Nil, //TODO: store plan as well
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

  def deploy(scheduler: MarathonSchedulerService, force: Boolean): Future[Boolean] = ???

  /*
  def deploy(scheduler: MarathonSchedulerService, force: Boolean): Future[Boolean] = {
    log.info(s"Deploy group ${target.id}: start:${toStart.map(_.id)}, stop:${toStop.map(_.id)}, scale:${toScale.map(_.id)}, restart:${toRestart.map(_.id)}")

    val updateFuture = toScale.map(to => scheduler.updateApp(to.id, AppUpdate(instances = Some(to.instances))).map(_ => true))
    val restartFuture = toRestart.map { app =>
      // call 'ceil' to ensure that the minimumHealthCapacity is not undershot because of rounding
      val keepAlive = (target.scalingStrategy.minimumHealthCapacity * app.instances).ceil.toInt
      scheduler.upgradeApp(
        app,
        keepAlive,
        // we need to start at least 1 instance
        target.scalingStrategy.maximumRunningFactor.map(x => math.max((x * app.instances).toInt, keepAlive + 1)),
        force = force)
    }
    val startFuture = toStart.map(scheduler.startApp(_).map(_ => true))
    val stopFuture = toStop.map(scheduler.stopApp(_).map(_ => true))
    val successFuture = Set(Future.successful(true)) //used, for immediate success, if no action is performed
    val deployFuture = Future.sequence(
      startFuture ++
        updateFuture ++
        restartFuture ++
        stopFuture ++
        successFuture).map(_.forall(identity))

    deployFuture andThen { case result => log.info(s"Deployment of ${target.id} has been finished $result") }
  }
  */
}

object DeploymentPlan {
  def empty() = DeploymentPlan("", Group.empty, Group.empty, Nil, Timestamp.now())

  def apply(id: String, original: Group, target: Group, version: Timestamp = Timestamp.now()): DeploymentPlan = {
    //lookup maps for original and target apps
    val originalApp: Map[String, AppDefinition] = original.transitiveApps.map(app => app.id -> app).toMap
    val targetApp: Map[String, AppDefinition] = target.transitiveApps.map(app => app.id -> app).toMap

    //compute the diff from original to target in terms of application
    val (toStart, toStop, toScale, toRestart) = {
      val isUpdate = targetApp.keySet.intersect(originalApp.keySet)
      val updateList = isUpdate.toList
      val origTarget = updateList.map(originalApp).zip(updateList.map(targetApp))
      (targetApp.keySet.filterNot(isUpdate.contains),
        originalApp.keySet.filterNot(isUpdate.contains),
        origTarget.filter{ case (from, to) => from.isOnlyScaleChange(to) }.map(_._2.id),
        origTarget.filter { case (from, to) => from.isUpgrade(to) }.map(_._2.id)
      )
    }
    val changedApplications = toStart ++ toRestart ++ toScale ++ toStop
    val (dependent, nonDependent) = target.dependencyList

    //apply the changes to the dependent applications
    val dependentSteps: List[DeploymentStep] = {
      var pass1 = List.empty[DeploymentStep]
      var pass2 = List.empty[DeploymentStep]
      for (app <- dependent.filter(a => changedApplications.contains(a.id))) {
        var pass1Actions = List.empty[DeploymentAction]
        var pass2Actions = List.empty[DeploymentAction]
        if (toStart.contains(app.id)) pass1Actions ::= StartApplication(app, app.instances)
        else if (toStop.contains(app.id)) pass1Actions ::= StopApplication(originalApp(app.id))
        else if (toScale.contains(app.id)) pass1Actions ::= ScaleApplication(app, app.instances)
        else {
          pass1Actions ::= RestartApplication(app,
            (target.scalingStrategy.minimumHealthCapacity * originalApp(app.id).instances).ceil.toInt,
            (target.scalingStrategy.minimumHealthCapacity * app.instances).ceil.toInt)
          pass2Actions ::= KillAllOldTasksOf(app)
          pass2Actions ::= ScaleApplication(app, app.instances)
        }
        if (pass1Actions.nonEmpty) pass1 ::= DeploymentStep(pass1Actions)
        if (pass2Actions.nonEmpty) pass2 ::= DeploymentStep(pass2Actions)
      }
      pass1.reverse ::: pass2
    }

    //apply the changes to the non dependent applications
    val nonDependentStep = DeploymentStep(nonDependent.toList.filter(a => changedApplications.contains(a.id)).map { app =>
      val origApp = originalApp(app.id)
      if (toStart.contains(app.id)) StartApplication(app, app.instances)
      else if (toStop.contains(app.id)) StopApplication(origApp)
      else if (toScale.contains(app.id)) ScaleApplication(app, app.instances)
      else RestartApplication(app, 0, app.instances)
    })

    //applications not included in the new group, but don't exist in the old one
    val unhandledStops = {
      val stops = toStop.filterNot(id => dependent.exists(_.id == id) || nonDependent.exists(_.id == id))
      if (stops.nonEmpty) List(DeploymentStep(stops.map(originalApp).map(StopApplication).toList)) else Nil
    }

    var finalSteps = dependentSteps match {
      case head :: rest => head + nonDependentStep :: rest ::: unhandledStops
      case Nil          => nonDependentStep :: unhandledStops
    }

    DeploymentPlan(id, original, target, finalSteps, version)
  }

  implicit object DeploymentPlanMigration extends Migration[DeploymentPlan] {
    def needsMigration(version: StorageVersion): Boolean = false
    def migrate(version: StorageVersion, obj: DeploymentPlan): DeploymentPlan = obj
  }
}
