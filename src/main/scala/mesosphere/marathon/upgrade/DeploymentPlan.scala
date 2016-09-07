package mesosphere.marathon.upgrade

import java.net.URL
import java.util.UUID

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.storage.repository.legacy.store.{CompressionConf, ZKData}
import mesosphere.marathon.state._
import mesosphere.marathon.storage.TwitterZk
import mesosphere.marathon.{MarathonConf, Protos}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.SortedMap
import scala.collection.immutable.Seq

sealed trait DeploymentAction extends Product with Serializable
sealed trait AppDeploymentAction extends DeploymentAction {
  val app: AppDefinition
}
sealed trait PodDeploymentAction extends DeploymentAction {
  val pod: PodDefinition
}

// application has not been started before
final case class StartApplication(app: AppDefinition, scaleTo: Int) extends AppDeploymentAction

case class StartPod(pod: PodDefinition, scaleTo: Int) extends PodDeploymentAction

// application is started, but the instance count should be changed
final case class ScaleApplication(
  app: AppDefinition,
  scaleTo: Int,
  sentencedToDeath: Option[Iterable[Instance]] = None) extends AppDeploymentAction

case class ScalePod(pod: PodDefinition, scaleTo: Int, sentencedToDeath: Seq[Instance] = Nil) extends PodDeploymentAction

// application is started, but shall be completely stopped
final case class StopApplication(app: AppDefinition) extends AppDeploymentAction

case class StopPod(pod: PodDefinition) extends PodDeploymentAction

// application is there but should be replaced
final case class RestartApplication(app: AppDefinition) extends AppDeploymentAction

case class RestartPod(pod: PodDefinition) extends PodDeploymentAction

// resolve and store artifacts for given app
final case class ResolveAppArtifacts(app: AppDefinition, url2Path: Map[URL, String]) extends AppDeploymentAction

case class ResolvePodArtifacts(pod: PodDefinition, url2Path: Map[URL, String]) extends PodDeploymentAction

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

  lazy val affectedApplications: Set[AppDefinition] =
    steps.flatMap(_.actions.collect { case a: AppDeploymentAction => a.app })(collection.breakOut)

  lazy val affectedPods: Set[PodDefinition] =
    steps.flatMap(_.actions.collect { case p: PodDeploymentAction => p.pod })(collection.breakOut)

  /** @return all ids of apps which are referenced in any deployment actions */
  lazy val affectedIds: Set[PathId] = affectedApplications.map(_.id) ++ affectedPods.map(_.id)

  def isAffectedBy(other: DeploymentPlan): Boolean =
    // FIXME: check for group change conflicts?
    affectedIds.intersect(other.affectedIds).nonEmpty

  lazy val createdOrUpdatedApps: Seq[AppDefinition] = {
    target.transitiveApps.toIndexedSeq.filter(app => affectedIds(app.id))
  }

  lazy val createdOrUpdatedPods: Seq[PodDefinition] = {
    target.transitivePodsById.values.filter(pod => affectedIds(pod.id)).toIndexedSeq
  }

  lazy val deletedApps: Seq[PathId] = {
    original.transitiveAppIds.diff(target.transitiveAppIds).toVector
  }

  lazy val deletedPods: Seq[PathId] = {
    original.transitivePodsById.keySet.diff(target.transitivePodsById.keySet).toVector
  }

  override def toString: String = {
    def appString(app: AppDefinition): String = {
      val cmdString = app.cmd.fold("")(cmd => ", cmd=\"" + cmd + "\"")
      val argsString = app.args.fold("")(args => ", args=\"" + args.mkString(" ") + "\"")
      val maybeDockerImage: Option[String] = app.container.flatMap(_.docker().map(_.image))
      val dockerImageString = maybeDockerImage.fold("")(image => ", image=\"" + image + "\"")

      s"App(${app.id}$dockerImageString$cmdString$argsString))"
    }

    def podString(pod: PodDefinition): String = {
      s"Pod(${pod.id})"
    }

    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(app, scale) => s"Start(${appString(app)}, instances=$scale)"
      case StartPod(pod, scale) => s"Start(${podString(pod)}, instances=$scale)"
      case StopApplication(app) => s"Stop(${appString(app)})"
      case StopPod(pod) => s"Stop(${podString(pod)})"
      case ScaleApplication(app, scale, toKill) =>
        val killTasksString =
          toKill.filter(_.nonEmpty).map(", killTasks=" + _.map(_.id.idString).mkString(",")).getOrElse("")
        s"Scale(${appString(app)}, instances=$scale$killTasksString)"
      case ScalePod(pod, scale, toKill) =>
        val killTasksString =
          toKill.map(_.id.idString).mkString(",")
        s"Scale(${podString(pod)}, instances=$scale, killTasks=$killTasksString)"
      case RestartApplication(app) => s"Restart(${appString(app)})"
      case RestartPod(pod) => s"Restart(${podString(pod)})"
      case ResolveAppArtifacts(app, urls) => s"Resolve(${appString(app)}, $urls})"
      case ResolvePodArtifacts(pod, urls) => s"Resolve(${podString(pod)}, $urls})"
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
  // scalastyle:off
  def dependencyOrderedSteps(original: Group, target: Group,
    toKill: Map[PathId, Iterable[Instance]]): Seq[DeploymentStep] = {
    val originalApps: Map[PathId, AppDefinition] = original.transitiveAppsById
    val originalPods = original.transitivePodsById

    val appsByLongestPath: SortedMap[Int, Set[AppDefinition]] = appsGroupedByLongestPath(target)

    val appSteps = appsByLongestPath.valuesIterator.map { (equivalenceClass: Set[AppDefinition]) =>
      val actions: Set[DeploymentAction] = equivalenceClass.flatMap { (newApp: AppDefinition) =>
        originalApps.get(newApp.id) match {
          // New app.
          case None =>
            Some(ScaleApplication(newApp, newApp.instances))

          // Scale-only change.
          case Some(oldApp) if oldApp.isOnlyScaleChange(newApp) =>
            Some(ScaleApplication(newApp, newApp.instances, toKill.get(newApp.id)))

          // Update or restart an existing app.
          case Some(oldApp) if oldApp.needsRestart(newApp) =>
            Some(RestartApplication(newApp))

          // Other cases require no action.
          case _ =>
            None
        }
      }

      DeploymentStep(actions.to[Seq])
    }.to[Seq]

    // TODO(PODS): Definitely not correct yet (pod steps)
    val podSteps = target.transitivePodsById.flatMap {
      case (id, pod) =>
        originalPods.get(id) match {
          case None =>
            Some(ScalePod(pod, pod.instances))
          case Some(old) if old.copy(instances = pod.instances) == pod =>
            Some(ScalePod(pod, pod.instances, toKill.getOrElse(pod.id, Nil).to[Seq]))
          // TODO(PODS) How to do restart?
          case _ =>
            None
        }
    }.to[Seq]
    DeploymentStep(podSteps) +: appSteps
  }
  // scalastyle:on

  /**
    * @param original the root group before the deployment
    * @param target the root group after the deployment
    * @param resolveArtifacts artifacts to resolve
    * @param version the version to use for new AppDefinitions (should be very close to now)
    * @param toKill specific tasks that should be killed
    * @return The deployment plan containing the steps necessary to get from the original to the target group definition
    */
  //scalastyle:off method.length
  def apply(
    original: Group,
    target: Group,
    resolveArtifacts: Seq[ResolveAppArtifacts] = Seq.empty,
    version: Timestamp = Timestamp.now(),
    toKill: Map[PathId, Iterable[Instance]] = Map.empty,
    id: Option[String] = None): DeploymentPlan = {

    // Lookup maps for original and target apps.
    val originalApps: Map[PathId, AppDefinition] = original.transitiveAppsById
    val originalPods: Map[PathId, PodDefinition] = original.transitivePodsById

    val targetApps: Map[PathId, AppDefinition] = target.transitiveAppsById
    val targetPods: Map[PathId, PodDefinition] = target.transitivePodsById

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
    //    instances.  These are scaled as needed in the dependency-ordered
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
