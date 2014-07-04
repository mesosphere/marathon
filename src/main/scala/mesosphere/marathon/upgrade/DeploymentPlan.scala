package mesosphere.marathon.upgrade

import java.util.UUID

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{ Group, PathId, Timestamp }
import mesosphere.util.Logging

sealed trait DeploymentAction {
  def app: AppDefinition
}

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
  def nonEmpty = actions.nonEmpty
}

final case class DeploymentPlan(
    id: String,
    original: Group,
    target: Group,
    steps: List[DeploymentStep],
    version: Timestamp) {

  def isEmpty: Boolean = steps.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def affectedApplicationIds: Set[PathId] = steps.flatMap(_.actions.map(_.app.id)).toSet

  def isAffectedBy(other: DeploymentPlan): Boolean = affectedApplicationIds.intersect(other.affectedApplicationIds).nonEmpty

  override def toString: String = {
    def appString(app: AppDefinition) = s"App(${app.id}, ${app.cmd}))"
    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(app, scale)      => s"Start(${appString(app)}, $scale)"
      case StopApplication(app)              => s"Stop(${appString(app)})"
      case ScaleApplication(app, scale)      => s"Scale(${appString(app)}, $scale)"
      case KillAllOldTasksOf(app)            => s"KillOld(${appString(app)})"
      case RestartApplication(app, from, to) => s"Restart(${appString(app)}, $from, $to)"
    }
    val stepString = steps.map("Step(" + _.actions.map(actionString) + ")").mkString("(", ", ", ")")
    s"DeploymentPlan($version, $stepString)"
  }
}

object DeploymentPlan extends Logging {
  def empty() = DeploymentPlan(UUID.randomUUID().toString, Group.empty, Group.empty, Nil, Timestamp.now())

  def apply(original: Group, target: Group, version: Timestamp = Timestamp.now()): DeploymentPlan = {
    log.info(s"Compute DeploymentPlan from $original to $target")

    //lookup maps for original and target apps
    val originalApp: Map[PathId, AppDefinition] = original.transitiveApps.map(app => app.id -> app).toMap
    val targetApp: Map[PathId, AppDefinition] = target.transitiveApps.map(app => app.id -> app).toMap

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

    //compute the restart actions: restart, kill, scale for one app
    def restartActions(app: AppDefinition, orig: AppDefinition) = (
      RestartApplication(app,
        (orig.upgradeStrategy.minimumHealthCapacity * orig.instances).ceil.toInt,
        (app.upgradeStrategy.minimumHealthCapacity * app.instances).ceil.toInt),
        KillAllOldTasksOf(app),
        ScaleApplication(app, app.instances)
    )

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
          val (restart, kill, scale) = restartActions(app, originalApp(app.id))
          pass1Actions ::= restart
          pass2Actions = kill :: scale :: pass2Actions
        }
        if (pass1Actions.nonEmpty) pass1 ::= DeploymentStep(pass1Actions)
        if (pass2Actions.nonEmpty) pass2 ::= DeploymentStep(pass2Actions)
      }
      pass1.reverse ::: pass2
    }

    //apply the changes to the non dependent applications
    val nonDependentSteps = nonDependent.toList.filter(a => changedApplications.contains(a.id)).flatMap { app =>
      def step(actions: DeploymentAction*) = List(DeploymentStep(actions.toList))
      if (toStart.contains(app.id)) step(StartApplication(app, app.instances))
      else if (toStop.contains(app.id)) step(StopApplication(originalApp(app.id)))
      else if (toScale.contains(app.id)) step(ScaleApplication(app, app.instances))
      else {
        val (restart, kill, scale) = restartActions(app, originalApp(app.id))
        List(DeploymentStep(List(restart)), DeploymentStep(List(kill, scale)))
      }
    }

    //applications not included in the new group, but exist in the old one
    val unhandledStops = {
      val stops = toStop.filterNot(id => dependent.exists(_.id == id) || nonDependent.exists(_.id == id))
      if (stops.nonEmpty) List(DeploymentStep(stops.map(originalApp).map(StopApplication).toList)) else Nil
    }

    var finalSteps = nonDependentSteps ++ dependentSteps ++ unhandledStops

    DeploymentPlan(UUID.randomUUID().toString, original, target, finalSteps.filter(_.nonEmpty), version)
  }
}
