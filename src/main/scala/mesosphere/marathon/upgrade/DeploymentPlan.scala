package mesosphere.marathon.upgrade

import java.net.URL
import java.util.UUID

import mesosphere.marathon.Protos
import mesosphere.marathon.state._
import mesosphere.util.Logging

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.collection.SortedMap

sealed trait DeploymentAction {
  def app: AppDefinition
}

// application has not been started before
final case class StartApplication(app: AppDefinition, scaleTo: Int) extends DeploymentAction

// application is started, but more instances should be started
final case class ScaleApplication(app: AppDefinition, scaleTo: Int) extends DeploymentAction

// application is started, but shall be completely stopped
final case class StopApplication(app: AppDefinition) extends DeploymentAction

// application is there but should be replaced
final case class RestartApplication(app: AppDefinition, scaleOldTo: Int, scaleNewTo: Int) extends DeploymentAction

// resolve and store artifacts for given app
final case class ResolveArtifacts(app: AppDefinition, url2Path: Map[URL, String]) extends DeploymentAction

final case class DeploymentStep(actions: Seq[DeploymentAction]) {
  def +(step: DeploymentStep): DeploymentStep = DeploymentStep(actions ++ step.actions)
  def nonEmpty(): Boolean = actions.nonEmpty
}

final case class DeploymentPlan(
    id: String,
    original: Group,
    target: Group,
    steps: Seq[DeploymentStep],
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

  /**
    * Returns a sorted map where each value is a subset of the supplied group's
    * apps and for all members of each subset, the longest path in the group's
    * dependency graph starting at that member is the same size.  The result
    * map is sorted by its keys, which are the lengths of the longest path
    * starting at the value set's elements.
    *
    * Rationale:
    *
    * #: AppDefinition → ℤ is an equivalence relation on AppDefinition where
    * the members of each equivalence class can be concurrently deployed.
    *
    * This follows naturally:
    *
    * The dependency graph is guaranteed to be free of cycles.
    * By definition for all α, β in some class X, # α = # β.
    * Choose any two apps α and β in a class X.
    * Suppose α transitively depends on β.
    * Then # α must be greater than # β.
    * Which is absurd.
    *
    * Furthermore, for any two apps α in class X and β in a class Y, X ≠ Y
    * where # α is less than # β: α does not transitively depend on β, by
    * similar logic.
    */
  private[upgrade] def appsGroupedByLongestPath(
    group: Group): SortedMap[Int, Set[AppDefinition]] = {

    import org.jgrapht.DirectedGraph
    import org.jgrapht.graph.DefaultEdge

    def longestPathFromVertex[V](g: DirectedGraph[V, DefaultEdge], vertex: V): Seq[V] = {
      val outgoingEdges: Set[DefaultEdge] =
        if (g.containsVertex(vertex)) g.outgoingEdgesOf(vertex).asScala.toSet
        else Set[DefaultEdge]()

      if (outgoingEdges.isEmpty)
        Seq(vertex)

      else
        outgoingEdges.map { e =>
          vertex +: longestPathFromVertex(g, g.getEdgeTarget(e))
        }.maxBy(_.length)

    }

    val unsortedEquivalenceClasses = group.transitiveApps.groupBy { app =>
      longestPathFromVertex(group.dependencyGraph, app).length
    }

    SortedMap(unsortedEquivalenceClasses.toSeq: _*)
  }

  /**
    * Returns a sequence of deployment steps, the order of which is derived
    * from the topology of the target group's dependency graph.
    */
  def dependencyOrderedSteps(original: Group, target: Group): Seq[DeploymentStep] = {

    // Result builder.
    val steps = Seq.newBuilder[DeploymentStep]

    val originalApps: Map[PathId, AppDefinition] =
      original.transitiveApps.map(app => app.id -> app).toMap

    val appsByLongestPath: SortedMap[Int, Set[AppDefinition]] = appsGroupedByLongestPath(target)
    appsByLongestPath.valuesIterator.foreach { equivalenceClass =>

      equivalenceClass.foreach { newApp =>
        val actions = Seq.newBuilder[DeploymentAction]
        originalApps.get(newApp.id) match {

          // New app.
          case None =>
            actions += ScaleApplication(newApp, newApp.instances)

          // Scale-only change.
          case Some(oldApp) if oldApp.isOnlyScaleChange(newApp) =>
            actions += ScaleApplication(newApp, newApp.instances)

          // Update existing app.
          case Some(oldApp) if oldApp != newApp =>
            val factor: Double = newApp.upgradeStrategy.minimumHealthCapacity
            val minimum: Int = math.min(
              oldApp.instances * factor,
              newApp.instances * factor
            ).ceil.toInt
            actions += RestartApplication(newApp, minimum, newApp.instances)

          // Other cases require no action.
          case _ => ()

        }
        steps += DeploymentStep(actions.result)
      }
    }

    steps.result
  }

  def apply(
    original: Group,
    target: Group,
    resolveArtifacts: Seq[ResolveArtifacts] = Seq.empty,
    version: Timestamp = Timestamp.now()): DeploymentPlan = {
    log.info(s"Compute DeploymentPlan from $original to $target")

    // Lookup maps for original and target apps.
    val originalApps: Map[PathId, AppDefinition] =
      original.transitiveApps.map(app => app.id -> app).toMap

    val targetApps: Map[PathId, AppDefinition] =
      target.transitiveApps.map(app => app.id -> app).toMap

    // A collection of deployment steps for this plan.
    val steps = Seq.newBuilder[DeploymentStep]

    // 0. Resolve artifacts.
    steps += DeploymentStep(resolveArtifacts)

    // 1. Destroy apps that do not exist in the target.
    steps += DeploymentStep(
      (originalApps -- targetApps.keys).valuesIterator.map { oldApp =>
        StopApplication(oldApp)
      }.to[Seq]
    )

    // 2. Start apps that do not exist in the original, requiring only 0
    //    instances.  These are scaled as needed in the depency-ordered
    //    steps that follow.
    steps += DeploymentStep(
      (targetApps -- originalApps.keys).valuesIterator.map { newApp =>
        StartApplication(newApp, 0)
      }.to[Seq]
    )

    // 3. For each app in each dependency class,
    //
    //      A. If this app is new, scale to the target number of instances.
    //
    //      B. If this is a scale change only, scale to the target number of
    //         instances.
    //
    //      C. Otherwise, if this is an app update:
    //         i. Scale down to the target minimumHealthCapacity fraction of
    //            the old app or the new app, whichever is less.
    //         ii. Restart the app, up to the new target number of instances.
    //
    steps ++= dependencyOrderedSteps(original, target)

    // Build the result.
    val result = DeploymentPlan(
      UUID.randomUUID().toString,
      original,
      target,
      steps.result.filter(_.actions.nonEmpty),
      version
    )

    log.info(s"Computed new deployment plan: $result")

    result
  }

}
