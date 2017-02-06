package mesosphere.marathon
package upgrade

import java.net.URL
import java.util.UUID

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.raml.{ ArgvCommand, ShellCommand }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import org.slf4j.LoggerFactory

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
      case _: ResolveArtifacts => "ResolveArtifacts"
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

// resolve and store artifacts for given runnable spec
case class ResolveArtifacts(runSpec: RunSpec, url2Path: Map[URL, String]) extends DeploymentAction

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
  * A deployment plan consists of the [[mesosphere.marathon.upgrade.DeploymentStep]]s necessary to
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

  lazy val affectedRunSpecs: Set[RunSpec] = steps.flatMap(_.actions.map(_.runSpec)).toSet

  /** @return all ids of apps which are referenced in any deployment actions */
  lazy val affectedRunSpecIds: Set[PathId] = steps.flatMap(_.actions.map(_.runSpec.id)).toSet

  def affectedAppIds: Set[PathId] = affectedRunSpecs.collect{ case app: AppDefinition => app }.map(_.id)
  def affectedPodIds: Set[PathId] = affectedRunSpecs.collect{ case pod: PodDefinition => pod }.map(_.id)

  def isAffectedBy(other: DeploymentPlan): Boolean =
    // FIXME: check for group change conflicts?
    affectedRunSpecIds.intersect(other.affectedRunSpecIds).nonEmpty

  lazy val createdOrUpdatedApps: Seq[AppDefinition] = {
    target.transitiveApps.filterAs(app => affectedRunSpecIds(app.id))(collection.breakOut)
  }

  lazy val deletedApps: Seq[PathId] = {
    original.transitiveAppIds.diff(target.transitiveAppIds).toIndexedSeq
  }

  lazy val createdOrUpdatedPods: Seq[PodDefinition] = {
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
      case ResolveArtifacts(app, urls) => s"Resolve(${appString(app)}, $urls})"
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
  private val log = LoggerFactory.getLogger(getClass)

  def empty: DeploymentPlan =
    DeploymentPlan(UUID.randomUUID().toString, RootGroup.empty, RootGroup.empty, Nil, Timestamp.now())

  /**
    * Perform a "layered" topological sort of all of the run specs.
    * The "layered" aspect groups the run specs that have the same length of dependencies for parallel deployment.
    */
  private[upgrade] def runSpecsGroupedByLongestPath(
    rootGroup: RootGroup): SortedMap[Int, Set[RunSpec]] = {

    import org.jgrapht.DirectedGraph
    import org.jgrapht.graph.DefaultEdge

    def longestPathFromVertex[V](g: DirectedGraph[V, DefaultEdge], vertex: V): Seq[V] = {
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

    val unsortedEquivalenceClasses = rootGroup.transitiveRunSpecs.groupBy { runSpec =>
      longestPathFromVertex(rootGroup.dependencyGraph, runSpec).length
    }

    SortedMap(unsortedEquivalenceClasses.toSeq: _*)
  }

  /**
    * Returns a sequence of deployment steps, the order of which is derived
    * from the topology of the target group's dependency graph.
    */
  def dependencyOrderedSteps(original: RootGroup, target: RootGroup,
    toKill: Map[PathId, Seq[Instance]]): Seq[DeploymentStep] = {
    val originalRunSpecs: Map[PathId, RunSpec] = original.transitiveRunSpecsById

    val runsByLongestPath: SortedMap[Int, Set[RunSpec]] = runSpecsGroupedByLongestPath(target)

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
    * @param resolveArtifacts artifacts to resolve
    * @param version the version to use for new RunSpec (should be very close to now)
    * @param toKill specific tasks that should be killed
    * @return The deployment plan containing the steps necessary to get from the original to the target group definition
    */
  def apply(
    original: RootGroup,
    target: RootGroup,
    resolveArtifacts: Seq[ResolveArtifacts] = Seq.empty,
    version: Timestamp = Timestamp.now(),
    toKill: Map[PathId, Seq[Instance]] = Map.empty,
    id: Option[String] = None): DeploymentPlan = {

    // Lookup maps for original and target run specs.
    val originalRuns: Map[PathId, RunSpec] = original.transitiveRunSpecsById

    val targetRuns: Map[PathId, RunSpec] = target.transitiveRunSpecsById

    // A collection of deployment steps for this plan.
    val steps = Seq.newBuilder[DeploymentStep]

    // 0. Resolve artifacts.
    steps += DeploymentStep(resolveArtifacts)

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
    steps ++= dependencyOrderedSteps(original, target, toKill)

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
