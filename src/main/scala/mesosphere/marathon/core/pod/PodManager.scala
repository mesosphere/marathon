package mesosphere.marathon.core.pod

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

trait PodManager {

  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def findAll(s: (PodDefinition) => Boolean): Source[PodDefinition, NotUsed]
  def find(id: PathId): Future[Option[PodDefinition]]
  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def delete(id: PathId, force: Boolean): Future[DeploymentPlan]
  def status(id: PathId): Future[Option[PodStatus]]
  def status(ids: Map[PathId, PodDefinition]): Future[Iterable[PodStatus]]
}
