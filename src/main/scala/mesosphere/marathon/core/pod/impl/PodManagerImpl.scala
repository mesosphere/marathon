package mesosphere.marathon.core.pod.impl

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{PodDefinition, PodManager}
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.repository.ReadOnlyPodRepository
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

case class PodManagerImpl(groupManager: GroupManager,
                          podRepository: ReadOnlyPodRepository)(implicit mat: Materializer) extends PodManager {
  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan] = ???

  def findAll(filter: (PodDefinition) => Boolean): Source[PodDefinition, NotUsed] = {
    podRepository.all().filter(filter)
  }

  def find(id: PathId): Future[Option[PodDefinition]] = {
    podRepository.get(id)
  }

  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan] = ???
  def delete(id: PathId, force: Boolean): Future[Option[DeploymentPlan]] = ???
  def status(id: PathId): Future[Option[PodStatus]] = ???

  override def status(): Source[PodStatus, NotUsed] = ???
}
