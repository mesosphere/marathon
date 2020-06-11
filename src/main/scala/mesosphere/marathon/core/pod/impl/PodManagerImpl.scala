package mesosphere.marathon
package core.pod.impl

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{PodDefinition, PodManager}
import mesosphere.marathon.state.{AbsolutePathId, Timestamp}

import scala.collection.immutable.Seq
import scala.concurrent.Future

case class PodManagerImpl(groupManager: GroupManager) extends PodManager {

  override def ids(): Set[AbsolutePathId] = groupManager.rootGroup().transitivePodIds.toSet

  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan] = {
    def createOrThrow(opt: Option[PodDefinition]) = opt
      .map(_ => throw ConflictingChangeException(s"A pod with id [${p.id}] already exists."))
      .getOrElse(p)
    groupManager.updatePod(p.id, createOrThrow, p.version, force)
  }

  def findAll(filter: (PodDefinition) => Boolean): Seq[PodDefinition] = {
    groupManager.rootGroup().transitivePods.iterator.filter(filter).toSeq
  }

  def find(id: AbsolutePathId): Option[PodDefinition] = groupManager.pod(id)

  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan] = {
    groupManager.updatePod(p.id, _ => p, p.version, force)
  }

  def delete(id: AbsolutePathId, force: Boolean): Future[DeploymentPlan] = {
    val version = Timestamp.now()
    groupManager.updateRoot(id.parent, _.removePod(id, version), version = version, force = force)
  }

  override def versions(id: AbsolutePathId): Source[Timestamp, NotUsed] = groupManager.podVersions(id).map(Timestamp(_))

  override def version(id: AbsolutePathId, version: Timestamp): Future[Option[PodDefinition]] =
    groupManager.podVersion(id, version.toOffsetDateTime)
}
