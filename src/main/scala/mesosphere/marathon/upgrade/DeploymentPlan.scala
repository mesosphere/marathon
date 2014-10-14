package mesosphere.marathon.upgrade

import java.net.URL
import java.util.UUID

import mesosphere.marathon.Protos
import mesosphere.marathon.state._
import mesosphere.util.Logging

import scala.collection.mutable.ListBuffer

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
//resolve and store artifacts for given app
final case class ResolveArtifacts(app: AppDefinition, url2Path: Map[URL, String]) extends DeploymentAction

final case class DeploymentStep(actions: List[DeploymentAction]) {
  def +(step: DeploymentStep): DeploymentStep = DeploymentStep(actions ++ step.actions)
  def nonEmpty(): Boolean = actions.nonEmpty
}

final case class DeploymentPlan(
    id: String,
    original: Group,
    target: Group,
    steps: List[DeploymentStep],
    version: Timestamp) extends MarathonState[Protos.DeploymentPlanDefinition, DeploymentPlan] {

  def isEmpty: Boolean = steps.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def affectedApplicationIds: Set[PathId] = steps.flatMap(_.actions.map(_.app.id)).toSet

  def isAffectedBy(other: DeploymentPlan): Boolean =
    affectedApplicationIds.intersect(other.affectedApplicationIds).nonEmpty

  override def toString: String = {
    def appString(app: AppDefinition): String = s"App(${app.id}, ${app.cmd}))"
    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(app, scale)      => s"Start(${appString(app)}, $scale)"
      case StopApplication(app)              => s"Stop(${appString(app)})"
      case ScaleApplication(app, scale)      => s"Scale(${appString(app)}, $scale)"
      case KillAllOldTasksOf(app)            => s"KillOld(${appString(app)})"
      case RestartApplication(app, from, to) => s"Restart(${appString(app)}, $from, $to)"
      case ResolveArtifacts(app, urls)       => s"Resolve(${appString(app)}, $urls})"
    }
    val stepString = steps.map("Step(" + _.actions.map(actionString) + ")").mkString("(", ", ", ")")
    s"DeploymentPlan($version, $stepString)"
  }

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan =
    mergeFromProto(Protos.DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: Protos.DeploymentPlanDefinition): DeploymentPlan = DeploymentPlan(
    original = Group.empty.mergeFromProto(msg.getOriginal),
    target = Group.empty.mergeFromProto(msg.getTarget),
    version = Timestamp(msg.getVersion)
  ).copy(id = msg.getId)

  override def toProto: Protos.DeploymentPlanDefinition =
    Protos.DeploymentPlanDefinition
      .newBuilder
      .setId(id)
      .setOriginal(original.toProto)
      .setTarget(target.toProto)
      .setVersion(version.toString)
      .build()
}

object DeploymentPlan extends Logging {
  def empty: DeploymentPlan =
    DeploymentPlan(UUID.randomUUID().toString, Group.empty, Group.empty, Nil, Timestamp.now())

  def fromProto(message: Protos.DeploymentPlanDefinition): DeploymentPlan = empty.mergeFromProto(message)

  def apply(
    original: Group,
    target: Group,
    resolveArtifacts: Seq[ResolveArtifacts] = Seq.empty,
    version: Timestamp = Timestamp.now()): DeploymentPlan = {
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

    // compute the restart actions: restart, kill, scale for one app
    // TODO: Let's create an ADT for this or refactor to a Seq
    def restartActions(
      app: AppDefinition,
      orig: AppDefinition): (DeploymentAction, DeploymentAction, DeploymentAction) = (
      RestartApplication(
        app,
        (orig.upgradeStrategy.minimumHealthCapacity * orig.instances).ceil.toInt,
        (app.upgradeStrategy.minimumHealthCapacity * app.instances).ceil.toInt
      ),
        KillAllOldTasksOf(app),
        ScaleApplication(app, app.instances)
    )

    //apply the changes to the dependent applications
    val dependentSteps: List[DeploymentStep] = {
      val pass1 = ListBuffer.empty[DeploymentStep]
      var pass2 = ListBuffer.empty[DeploymentStep]
      var pass3 = ListBuffer.empty[DeploymentStep]
      for (app <- dependent.filter(a => changedApplications.contains(a.id))) {
        val pass1Actions = ListBuffer.empty[DeploymentAction]
        val pass2Actions = ListBuffer.empty[DeploymentAction]
        val pass3Actions = ListBuffer.empty[DeploymentAction]
        if (toStart.contains(app.id)) pass1Actions += StartApplication(app, app.instances)
        else if (toStop.contains(app.id)) pass1Actions += StopApplication(originalApp(app.id))
        else if (toScale.contains(app.id)) pass1Actions += ScaleApplication(app, app.instances)
        else {
          val (restart, kill, scale) = restartActions(app, originalApp(app.id))
          pass1Actions += restart
          pass2Actions += kill
          pass3Actions += scale
        }
        if (pass1Actions.nonEmpty) pass1 += DeploymentStep(pass1Actions.result())
        if (pass2Actions.nonEmpty) pass2 += DeploymentStep(pass2Actions.result())
        if (pass3Actions.nonEmpty) pass3 += DeploymentStep(pass3Actions.result())
      }
      (pass1 ++ pass2.reverse ++ pass3).result()
    }

    //apply the changes to the non dependent applications
    val nonDependentSteps = {
      val step1 = ListBuffer.empty[DeploymentAction]
      val step2 = ListBuffer.empty[DeploymentAction]
      val step3 = ListBuffer.empty[DeploymentAction]
      nonDependent.toList.filter(a => changedApplications.contains(a.id)).foreach { app =>
        if (toStart.contains(app.id)) step1 += StartApplication(app, app.instances)
        else if (toStop.contains(app.id)) step1 += StopApplication(originalApp(app.id))
        else if (toScale.contains(app.id)) step1 += ScaleApplication(app, app.instances)
        else {
          val (restart, kill, scale) = restartActions(app, originalApp(app.id))
          step1 += restart
          step2 += kill
          step3 += scale
        }
      }
      List(DeploymentStep(step1.result()), DeploymentStep(step2.result()), DeploymentStep(step3.result()))
    }

    //applications not included in the new group, but exist in the old one
    val unhandledStops = {
      val stops = toStop.filterNot(id => dependent.exists(_.id == id) || nonDependent.exists(_.id == id))
      if (stops.nonEmpty) List(DeploymentStep(stops.map(originalApp).map(StopApplication).toList)) else Nil
    }

    //resolve artifact dependencies .
    val toResolve = List(DeploymentStep(resolveArtifacts.toList))

    val finalSteps = toResolve ++ nonDependentSteps ++ dependentSteps ++ unhandledStops

    DeploymentPlan(UUID.randomUUID().toString, original, target, finalSteps.filter(_.nonEmpty), version)
  }
}
