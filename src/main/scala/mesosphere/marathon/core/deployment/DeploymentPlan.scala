package mesosphere.marathon
package core.deployment

import java.util.UUID

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.deployment.impl.DeploymentPlanReverter
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.{ ArgvCommand, ShellCommand }
import mesosphere.marathon.state._

import scala.collection.SortedMap

sealed trait DeploymentAction {
  def runSpec: RunSpec
}

object DeploymentAction {

  def actionName(action: DeploymentAction): String = {
    val actionType = action.runSpec match {
      case app: AppDefinition => "Application"
      case pod: PodDefinition => "Pod"
    }
    action match {
      case _: StartApplication => s"Start$actionType"
      case _: StopApplication => s"Stop$actionType"
      case _: ScaleApplication => s"Scale$actionType"
      case _: RestartApplication => s"Restart$actionType"
    }
  }
}

// runnable spec has not been started before
case class StartApplication(runSpec: RunSpec, scaleTo: Int) extends DeploymentAction

// runnable spec is started, but the instance count should be changed
// TODO: Why is there an Option[Seq[]]?!
case class ScaleApplication(
  runSpec: RunSpec,
  scaleTo: Int,
  sentencedToDeath: Option[Seq[Instance]] = None) extends DeploymentAction

// runnable spec is started, but shall be completely stopped
case class StopApplication(runSpec: RunSpec) extends DeploymentAction

// runnable spec is there but should be replaced
case class RestartApplication(runSpec: RunSpec) extends DeploymentAction

/**
  * One step in a deployment plan.
  * The contained actions may be executed in parallel.
  *
  * @param actions the actions of this step that maybe executed in parallel
  */
case class DeploymentStep(actions: Seq[DeploymentAction]) {
  def +(step: DeploymentStep): DeploymentStep = DeploymentStep(actions ++ step.actions)
  def nonEmpty(): Boolean = actions.nonEmpty
}

/**
  * Current state of the deployment. Has the deployment plan, current step information [[DeploymentStep]] with the
  * step index and the corresponding readiness checks results [[core.readiness.ReadinessCheckResult]] for the app instances.
  *
  * @param plan deployment plan
  * @param step current deployment step
  * @param stepIndex current deployment step index
  * @param readinessChecks a map with readiness check results for app instances
  */
case class DeploymentStepInfo(
    plan: DeploymentPlan,
    step: DeploymentStep,
    stepIndex: Int,
    readinessChecks: Map[Task.Id, ReadinessCheckResult] = Map.empty) {
  lazy val readinessChecksByApp: Map[PathId, Seq[ReadinessCheckResult]] = {
    readinessChecks.values.groupBy(_.taskId.runSpecId).map { case (k, v) => k -> v.to[Seq] }.withDefaultValue(Seq.empty)
  }
}

/**
  * A deployment plan consists of the [[mesosphere.marathon.core.deployment.DeploymentStep]]s necessary to
  * change the group state from original to target.
  *
  * The steps are executed sequentially after each other. The actions within a
  * step maybe executed in parallel.
  *
  * See `mesosphere.marathon.upgrade.DeploymentPlan.appsGroupedByLongestPath` to
  * understand how we can guarantee that all dependencies for a step are fulfilled
  * by prior steps.
  */
case class DeploymentPlan(
    id: String,
    original: RootGroup,
    target: RootGroup,
    steps: Seq[DeploymentStep],
    version: Timestamp) {

  /**
    * Reverts this plan by applying the reverse changes to the given Group.
    */
  def revert(rootGroup: RootGroup): RootGroup = DeploymentPlanReverter.revert(original, target)(rootGroup)

  lazy val isEmpty: Boolean = steps.isEmpty

  lazy val nonEmpty: Boolean = !isEmpty

  lazy val affectedRunSpecs: Set[RunSpec] = steps.flatMap(_.actions.map(_.runSpec))(collection.breakOut)

  /** @return all ids of apps which are referenced in any deployment actions */
  lazy val affectedRunSpecIds: Set[PathId] = steps.flatMap(_.actions.map(_.runSpec.id))(collection.breakOut)

  def affectedAppIds: Set[PathId] = affectedRunSpecs.collect{ case app: AppDefinition => app.id }
  def affectedPodIds: Set[PathId] = affectedRunSpecs.collect{ case pod: PodDefinition => pod.id }

  def isAffectedBy(other: DeploymentPlan): Boolean =
    // FIXME: check for group change conflicts?
    affectedRunSpecIds.intersect(other.affectedRunSpecIds).nonEmpty

  lazy val createdOrUpdatedApps: Seq[AppDefinition] = {
    import mesosphere.marathon.stream.Implicits.toRichTraversableLike
    target.transitiveApps.filterAs(app => affectedRunSpecIds(app.id))(collection.breakOut)
  }

  lazy val deletedApps: Seq[PathId] = {
    original.transitiveAppIds.diff(target.transitiveAppIds).toIndexedSeq
  }

  lazy val createdOrUpdatedPods: Seq[PodDefinition] = {
    import mesosphere.marathon.stream.Implicits.toRichTraversableLike
    target.transitivePodsById.values.filterAs(pod => affectedRunSpecIds(pod.id))(collection.breakOut)
  }

  lazy val deletedPods: Seq[PathId] = {
    original.transitivePodsById.keySet.diff(target.transitivePodsById.keySet).toIndexedSeq
  }

  override def toString: String = {
    def specString(spec: RunSpec): String = spec match {
      case app: AppDefinition => appString(app)
      case pod: PodDefinition => podString(pod)

    }
    def podString(pod: PodDefinition): String = {
      val containers = pod.containers.map(containerString).mkString(", ")
      s"""Pod(id="${pod.id}", containers=[$containers])"""
    }
    def containerString(container: MesosContainer): String = {
      val command = container.exec.map{
        _.command match {
          case ShellCommand(shell) => s""", cmd="$shell""""
          case ArgvCommand(args) => s""", args="${args.mkString(", ")}""""
        }
      }
      val image = container.image.fold("")(image => s""", image="$image"""")
      s"""Container(name="${container.name}$image$command}")"""
    }
    def appString(app: RunSpec): String = {
      val cmdString = app.cmd.fold("")(cmd => ", cmd=\"" + cmd + "\"")
      val argsString = app.args.map(args => ", args=\"" + args.mkString(" ") + "\"")
      val maybeDockerImage: Option[String] = app.container.flatMap(_.docker.map(_.image))
      val dockerImageString = maybeDockerImage.fold("")(image => ", image=\"" + image + "\"")

      s"App(${app.id}$dockerImageString$cmdString$argsString))"
    }
    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(spec, scale) => s"Start(${specString(spec)}, instances=$scale)"
      case StopApplication(spec) => s"Stop(${specString(spec)})"
      case ScaleApplication(spec, scale, toKill) =>
        val killTasksString =
          toKill.withFilter(_.nonEmpty).map(", killTasks=" + _.map(_.instanceId.idString).mkString(",")).getOrElse("")
        s"Scale(${appString(spec)}, instances=$scale$killTasksString)"
      case RestartApplication(app) => s"Restart(${appString(app)})"
    }
    val stepString =
      if (steps.nonEmpty) {
        steps
          .map { _.actions.map(actionString).mkString("  * ", "\n  * ", "") }
          .zipWithIndex
          .map { case (stepsString, index) => s"step ${index + 1}:\n$stepsString" }
          .mkString("\n", "\n", "")
      } else " NO STEPS"
    s"DeploymentPlan id=$id,$version$stepString\n"
  }
}

object DeploymentPlan {

  def empty: DeploymentPlan =
    DeploymentPlan(UUID.randomUUID().toString, RootGroup.empty, RootGroup.empty, Nil, Timestamp.now())

  /**
    * Perform a "layered" topological sort of all of the run specs that are going to be deployed.
    * The "layered" aspect groups the run specs that have the same length of dependencies for parallel deployment.
    */
  private[deployment] def runSpecsGroupedByLongestPath(
    affectedRunSpecIds: Set[PathId],
    rootGroup: RootGroup): SortedMap[Int, Set[RunSpec]] = {

    import org.jgrapht.DirectedGraph
    import org.jgrapht.graph.DefaultEdge

    def longestPathFromVertex[V](g: DirectedGraph[V, DefaultEdge], vertex: V): Seq[V] = {
      import mesosphere.marathon.stream.Implicits.RichSet

      val outgoingEdges: Set[DefaultEdge] =
        if (g.containsVertex(vertex)) g.outgoingEdgesOf(vertex)
        else Set.empty[DefaultEdge]

      if (outgoingEdges.isEmpty)
        Seq(vertex)

      else
        outgoingEdges.map { e =>
          vertex +: longestPathFromVertex(g, g.getEdgeTarget(e))
        }.maxBy(_.length)

    }

    val unsortedEquivalenceClasses = rootGroup.transitiveRunSpecs.filter(spec => affectedRunSpecIds.contains(spec.id)).groupBy { runSpec =>
      longestPathFromVertex(rootGroup.dependencyGraph, runSpec).length
    }

    SortedMap(unsortedEquivalenceClasses.toSeq: _*)
  }

  /**
    * Returns a sequence of deployment steps, the order of which is derived
    * from the topology of the target group's dependency graph.
    */
  def dependencyOrderedSteps(original: RootGroup, target: RootGroup, affectedIds: Set[PathId],
    toKill: Map[PathId, Seq[Instance]]): Seq[DeploymentStep] = {
    val originalRunSpecs: Map[PathId, RunSpec] = original.transitiveRunSpecsById

    val runsByLongestPath: SortedMap[Int, Set[RunSpec]] = runSpecsGroupedByLongestPath(affectedIds, target)

    runsByLongestPath.values.map { (equivalenceClass: Set[RunSpec]) =>
      val actions: Set[DeploymentAction] = equivalenceClass.flatMap { (newSpec: RunSpec) =>
        originalRunSpecs.get(newSpec.id) match {
          // New run spec.
          case None =>
            Some(ScaleApplication(newSpec, newSpec.instances))

          // Scale-only change.
          case Some(oldSpec) if oldSpec.isOnlyScaleChange(newSpec) =>
            Some(ScaleApplication(newSpec, newSpec.instances, toKill.get(newSpec.id)))

          // Update or restart an existing run spec.
          case Some(oldSpec) if oldSpec.needsRestart(newSpec) =>
            Some(RestartApplication(newSpec))

          // Other cases require no action.
          case _ =>
            None
        }
      }

      DeploymentStep(actions.to[Seq])
    }(collection.breakOut)
  }

  /**
    * @param original the root group before the deployment
    * @param target the root group after the deployment
    * @param version the version to use for new RunSpec (should be very close to now)
    * @param toKill specific tasks that should be killed
    * @return The deployment plan containing the steps necessary to get from the original to the target group definition
    */
  def apply(
    original: RootGroup,
    target: RootGroup,
    version: Timestamp = Timestamp.now(),
    toKill: Map[PathId, Seq[Instance]] = Map.empty,
    id: Option[String] = None): DeploymentPlan = {

    // Lookup maps for original and target run specs.
    val originalRuns: Map[PathId, RunSpec] = original.transitiveRunSpecsById

    val targetRuns: Map[PathId, RunSpec] = target.transitiveRunSpecsById

    // A collection of deployment steps for this plan.
    val steps = Seq.newBuilder[DeploymentStep]

    // 1. Destroy run specs that do not exist in the target.
    steps += DeploymentStep(
      (originalRuns -- targetRuns.keys).values.map { oldRun =>
        StopApplication(oldRun)
      }(collection.breakOut)
    )

    // 2. Start run specs that do not exist in the original, requiring only 0
    //    instances.  These are scaled as needed in the dependency-ordered
    //    steps that follow.
    steps += DeploymentStep(
      (targetRuns -- originalRuns.keys).values.map { newRun =>
        StartApplication(newRun, 0)
      }(collection.breakOut)
    )

    // applications that are either new or the specs are different should be considered for the dependency graph
    val addedOrChanged: Set[PathId] = targetRuns.collect {
      case (runSpecId, spec) if (!originalRuns.contains(runSpecId) ||
        (originalRuns.contains(runSpecId) && originalRuns(runSpecId) != spec)) =>
        // the above could be optimized/refined further by checking the version info. The tests are actually
        // really bad about structuring this correctly though, so for now, we just make sure that
        // the specs are different (or brand new)
        runSpecId
    }(collection.breakOut)
    val affectedApplications = addedOrChanged ++ (originalRuns.keySet -- targetRuns.keySet)

    // 3. For each runSpec in each dependency class,
    //
    //      A. If this runSpec is new, scale to the target number of instances.
    //
    //      B. If this is a scale change only, scale to the target number of
    //         instances.
    //
    //      C. Otherwise, if this is an runSpec update:
    //         i. Scale down to the target minimumHealthCapacity fraction of
    //            the old runSpec or the new runSpec, whichever is less.
    //         ii. Restart the runSpec, up to the new target number of instances.
    //
    steps ++= dependencyOrderedSteps(original, target, affectedApplications, toKill)

    // Build the result.
    val result = DeploymentPlan(
      id.getOrElse(UUID.randomUUID().toString),
      original,
      target,
      steps.result().filter(_.actions.nonEmpty),
      version
    )

    result
  }

  def deploymentPlanValidator(): Validator[DeploymentPlan] = {
    validator[DeploymentPlan] { plan =>
      plan.createdOrUpdatedApps as "app" is every(valid(AppDefinition.updateIsValid(plan.original)))
    }
  }
}
