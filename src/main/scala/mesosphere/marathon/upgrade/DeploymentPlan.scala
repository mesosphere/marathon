package mesosphere.marathon.upgrade

import java.net.URL
import java.util.UUID

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ PodDefinition, MesosContainer }
import mesosphere.marathon.raml.{ ArgvCommand, ShellCommand }
import mesosphere.marathon.storage.repository.legacy.store.{ CompressionConf, ZKData }
import mesosphere.marathon.state._
import mesosphere.marathon.storage.TwitterZk
import mesosphere.marathon.{ MarathonConf, Protos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.SortedMap
import scala.collection.immutable.Seq

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
final case class StartApplication(runSpec: RunSpec, scaleTo: Int) extends DeploymentAction

// runnable spec is started, but the instance count should be changed
final case class ScaleApplication(
  runSpec: RunSpec,
  scaleTo: Int,
  sentencedToDeath: Option[Iterable[Instance]] = None) extends DeploymentAction

// runnable spec is started, but shall be completely stopped
final case class StopApplication(runSpec: RunSpec) extends DeploymentAction

// runnable spec is there but should be replaced
final case class RestartApplication(runSpec: RunSpec) extends DeploymentAction

// resolve and store artifacts for given runnable spec
final case class ResolveArtifacts(runSpec: RunSpec, url2Path: Map[URL, String]) extends DeploymentAction

/**
  * One step in a deployment plan.
  * The contained actions may be executed in parallel.
  *
  * @param actions the actions of this step that maybe executed in parallel
  */
final case class DeploymentStep(actions: Seq[DeploymentAction]) {
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
final case class DeploymentPlan(
    id: String,
    original: Group,
    target: Group,
    steps: Seq[DeploymentStep],
    version: Timestamp) extends MarathonState[Protos.DeploymentPlanDefinition, DeploymentPlan] {

  /**
    * Reverts this plan by applying the reverse changes to the given Group.
    */
  def revert(group: Group): Group = DeploymentPlanReverter.revert(original, target)(group)

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
    target.transitiveApps.toIndexedSeq.filter(app => affectedRunSpecIds(app.id))
  }

  lazy val deletedApps: Seq[PathId] = {
    original.transitiveAppIds.diff(target.transitiveAppIds).toVector
  }

  lazy val createdOrUpdatedPods: Seq[PodDefinition] = {
    target.transitivePodsById.values.toIndexedSeq.filter(pod => affectedRunSpecIds(pod.id))
  }

  lazy val deletedPods: Seq[PathId] = {
    original.transitivePodsById.keySet.diff(target.transitivePodsById.keySet).toVector
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
      val maybeDockerImage: Option[String] = app.container.flatMap(_.docker().map(_.image))
      val dockerImageString = maybeDockerImage.fold("")(image => ", image=\"" + image + "\"")

      s"App(${app.id}$dockerImageString$cmdString$argsString))"
    }
    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(spec, scale) => s"Start(${specString(spec)}, instances=$scale)"
      case StopApplication(spec) => s"Stop(${specString(spec)})"
      case ScaleApplication(spec, scale, toKill) =>
        val killTasksString =
          toKill.filter(_.nonEmpty).map(", killTasks=" + _.map(_.instanceId.idString).mkString(",")).getOrElse("")
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
    s"DeploymentPlan $version$stepString\n"
  }

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan =
    mergeFromProto(Protos.DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: Protos.DeploymentPlanDefinition): DeploymentPlan = DeploymentPlan(
    original = Group.empty.mergeFromProto(msg.getDeprecatedOriginal),
    target = Group.empty.mergeFromProto(msg.getDeprecatedTarget),
    version = Timestamp(msg.getTimestamp),
    id = Some(msg.getId)
  )

  override def toProto: Protos.DeploymentPlanDefinition =
    Protos.DeploymentPlanDefinition
      .newBuilder
      .setId(id)
      .setDeprecatedOriginal(original.toProto)
      .setDeprecatedTarget(target.toProto)
      .setTimestamp(version.toString)
      .build()
}

object DeploymentPlan {
  private val log = LoggerFactory.getLogger(getClass)

  def empty: DeploymentPlan =
    DeploymentPlan(UUID.randomUUID().toString, Group.empty, Group.empty, Nil, Timestamp.now())

  def fromProto(message: Protos.DeploymentPlanDefinition): DeploymentPlan = empty.mergeFromProto(message)

  /**
    * Returns a sorted map where each value is a subset of the supplied group's
    * runs and for all members of each subset, the longest path in the group's
    * dependency graph starting at that member is the same size.  The result
    * map is sorted by its keys, which are the lengths of the longest path
    * starting at the value set's elements.
    *
    * Rationale:
    *
    * #: RunSpec → ℤ is an equivalence relation on RunSpec where
    * the members of each equivalence class can be concurrently deployed.
    *
    * This follows naturally:
    *
    * The dependency graph is guaranteed to be free of cycles.
    * By definition for all α, β in some class X, # α = # β.
    * Choose any two runs α and β in a class X.
    * Suppose α transitively depends on β.
    * Then # α must be greater than # β.
    * Which is absurd.
    *
    * Furthermore, for any two runs α in class X and β in a class Y, X ≠ Y
    * where # α is less than # β: α does not transitively depend on β, by
    * similar logic.
    */
  private[upgrade] def runSpecsGroupedByLongestPath(
    group: Group): SortedMap[Int, Set[RunSpec]] = {

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

    val unsortedEquivalenceClasses = group.transitiveRunSpecs.groupBy { runSpec =>
      longestPathFromVertex(group.dependencyGraph, runSpec).length
    }

    SortedMap(unsortedEquivalenceClasses.toSeq: _*)
  }

  /**
    * Returns a sequence of deployment steps, the order of which is derived
    * from the topology of the target group's dependency graph.
    */
  def dependencyOrderedSteps(original: Group, target: Group,
    toKill: Map[PathId, Iterable[Instance]]): Seq[DeploymentStep] = {
    val originalRunSpecs: Map[PathId, RunSpec] = original.transitiveRunSpecsById

    val runsByLongestPath: SortedMap[Int, Set[RunSpec]] = runSpecsGroupedByLongestPath(target)

    runsByLongestPath.valuesIterator.map { (equivalenceClass: Set[RunSpec]) =>
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
    }.to[Seq]
  }

  /**
    * @param original the root group before the deployment
    * @param target the root group after the deployment
    * @param resolveArtifacts artifacts to resolve
    * @param version the version to use for new RunSpec (should be very close to now)
    * @param toKill specific tasks that should be killed
    * @return The deployment plan containing the steps necessary to get from the original to the target group definition
    */
  //scalastyle:off method.length
  def apply(
    original: Group,
    target: Group,
    resolveArtifacts: Seq[ResolveArtifacts] = Seq.empty,
    version: Timestamp = Timestamp.now(),
    toKill: Map[PathId, Iterable[Instance]] = Map.empty,
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
      (originalRuns -- targetRuns.keys).valuesIterator.map { oldRun =>
      StopApplication(oldRun)
    }.to[Seq]
    )

    // 2. Start run specs that do not exist in the original, requiring only 0
    //    instances.  These are scaled as needed in the dependency-ordered
    //    steps that follow.
    steps += DeploymentStep(
      (targetRuns -- originalRuns.keys).valuesIterator.map { newRun =>
      StartApplication(newRun, 0)
    }.to[Seq]
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

  def deploymentPlanValidator(conf: MarathonConf): Validator[DeploymentPlan] = {
    val maxSize = conf.zooKeeperMaxNodeSize()
    val maxSizeError = s"""The way we persist data in ZooKeeper would exceed the maximum ZK node size ($maxSize bytes).
                         |You can adjust this value via --zk_max_node_size, but make sure this value is compatible with
                         |your ZooKeeper ensemble!
                         |See: http://zookeeper.apache.org/doc/r3.3.1/zookeeperAdmin.html#Unsafe+Options""".stripMargin

    val notBeTooBig = isTrue[DeploymentPlan](maxSizeError) { plan =>
      if (conf.internalStoreBackend() == TwitterZk.StoreName) {
        val compressionConf = CompressionConf(conf.zooKeeperCompressionEnabled(), conf.zooKeeperCompressionThreshold())
        val zkDataProto = ZKData(s"deployment-${plan.id}", UUID.fromString(plan.id), plan.toProto.toByteArray)
          .toProto(compressionConf)
        zkDataProto.toByteArray.length < maxSize
      } else {
        // we could try serializing the proto then gzip compressing it for the new ZK backend, but should we?
        true
      }
    }

    validator[DeploymentPlan] { plan =>
      plan.createdOrUpdatedApps as "app" is every(valid(AppDefinition.updateIsValid(plan.original)))
      plan should notBeTooBig
    }
  }
}
