package mesosphere.marathon.core.pod

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

trait PodManager {
  def ids(): Source[PathId, NotUsed]
  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def findAll(s: (PodDefinition) => Boolean): Source[PodDefinition, NotUsed]
  def find(id: PathId): Future[Option[PodDefinition]]
  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def delete(id: PathId, force: Boolean): Future[DeploymentPlan]
  def versions(id: PathId): Source[Timestamp, NotUsed]
  def version(id: PathId, version: Timestamp): Future[Option[PodDefinition]]
}
