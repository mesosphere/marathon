package mesosphere.marathon.core.pod.impl

import java.time.{ Clock, OffsetDateTime }

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.{ ConflictingChangeException }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.{ PodStatus }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

class PodManagerImpl(
  groupManager: GroupManager,
  statusService: PodStatusService)(implicit
  ctx: ExecutionContext,
    clock: Clock) extends PodManager {

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

  def status(id: PathId): Future[Option[PodStatus]] = statusService.selectPodStatus(id)
}
