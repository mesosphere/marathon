package mesosphere.marathon
package core.pod

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.core.deployment.DeploymentPlan

import scala.concurrent.Future

trait PodManager {
  def ids(): Set[PathId]
  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def findAll(s: (PodDefinition) => Boolean): Seq[PodDefinition]
  def find(id: PathId): Option[PodDefinition]
  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def delete(id: PathId, force: Boolean): Future[DeploymentPlan]
  def versions(id: PathId): Source[Timestamp, NotUsed]
  def version(id: PathId, version: Timestamp): Future[Option[PodDefinition]]
}
