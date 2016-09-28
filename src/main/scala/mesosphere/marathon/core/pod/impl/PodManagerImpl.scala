package mesosphere.marathon.core.pod.impl

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.ConflictingChangeException
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.storage.repository.ReadOnlyPodRepository
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

case class PodManagerImpl(
    groupManager: GroupManager,
    podRepository: ReadOnlyPodRepository)(implicit ctx: ExecutionContext) extends PodManager {

  override def ids(): Source[PathId, NotUsed] =
    Source.fromFuture(groupManager.rootGroup()).mapConcat(_.transitivePodsById.keySet)

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

  override def versions(id: PathId): Source[Timestamp, NotUsed] = {
    podRepository.versions(id).map(Timestamp(_))
  }

  override def version(id: PathId, version: Timestamp): Future[Option[PodDefinition]] = {
    podRepository.getVersion(id, version.toOffsetDateTime)
  }
}
