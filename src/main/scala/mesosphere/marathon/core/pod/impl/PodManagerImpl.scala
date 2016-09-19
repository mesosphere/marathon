package mesosphere.marathon.core.pod.impl

import java.time.{ Clock, OffsetDateTime }

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.{ ConflictingChangeException, DeploymentService }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.{ PodInstanceState, PodInstanceStatus, PodState, PodStatus, Raml }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ Await, ExecutionContext, Future }

class PodManagerImpl(
    groupManager: GroupManager,
    tracker: InstanceTracker,
    deploymentService: DeploymentService)(implicit
  ctx: ExecutionContext,
    clock: Clock) extends PodManager {

  import PodManagerImpl._

  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan] = {
    def createOrThrow(opt: Option[PodDefinition]) = opt
      .map(_ => throw ConflictingChangeException(s"A pod with id [${p.id}] already exists."))
      .getOrElse(p)
    groupManager.updatePod(p.id, createOrThrow, p.version, force)
  }

  def findAll(filter: (PodDefinition) => Boolean): Source[PodDefinition, NotUsed] = {
    val pods = groupManager.rootGroup().map(_.transitivePodsById.values.filter(filter).to[Seq])
    Source.fromFuture(pods).mapConcat(identity)
  }

  def find(id: PathId): Future[Option[PodDefinition]] = groupManager.pod(id)

  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan] =
    groupManager.updatePod(p.id, _ => p, p.version, force)

  def delete(id: PathId, force: Boolean): Future[DeploymentPlan] = {
    groupManager.update(id.parent, _.removePod(id), force = force)
  }

  def status(id: PathId): Future[Option[PodStatus]] = {
    find(id).flatMap { maybePod =>
      maybePod match {
        case Some(pod) =>
          status(Map(pod.id -> pod)).map(_.headOption)
        case None =>
          Future.successful(None)
      }
    }
  }

  override def status(ids: Map[PathId, PodDefinition]): Future[Iterable[PodStatus]] = {
    val now = OffsetDateTime.now(clock)
    Future.sequence(ids.map {
      case (id, podDef) =>
        val instances = tracker.specInstancesSync(id)
        val instanceStatus = instances.map(Instance.asPodInstanceStatus(podDef, _)).toVector
        val statusSince = if (instances.isEmpty) now else instanceStatus.map(_.statusSince).max
        val isPodTerminating: Future[Boolean] = deploymentService.listRunningDeployments().map { infos =>
          infos.exists(_.plan.deletedPods.contains(id))
        }
        val stateFuture = podState(podDef.instances, instanceStatus, isPodTerminating)

        stateFuture.map { state =>
          // TODO(jdef) pods need termination history
          PodStatus(
            id = id.toString,
            spec = Raml.toRaml(podDef),
            instances = instanceStatus,
            status = state,
            statusSince = statusSince,
            lastUpdated = now,
            lastChanged = statusSince
          )
        }
    })
  }
}

object PodManagerImpl {

  def podState(
    expectedInstanceCount: Integer,
    instanceStatus: Seq[PodInstanceStatus],
    isPodTerminating: Future[Boolean])(implicit ec: ExecutionContext): Future[PodState] =

    async {
      if (await(isPodTerminating)) {
        PodState.Terminal
      } else {
        // TODO(jdef) add an "oversized" condition, or related message of num-current-instances > expected?
        if (instanceStatus.map(_.status).count(_ == PodInstanceState.Stable) >= expectedInstanceCount) {
          PodState.Stable
        } else {
          PodState.Degraded
        }
      }
    }
}
