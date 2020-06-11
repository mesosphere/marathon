package mesosphere.marathon
package core.pod

import akka.NotUsed
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state.{AbsolutePathId, Timestamp}

import scala.concurrent.Future

trait PodManager {
  def ids(): Set[AbsolutePathId]
  def create(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def findAll(s: (PodDefinition) => Boolean): Seq[PodDefinition]
  def find(id: AbsolutePathId): Option[PodDefinition]
  def update(p: PodDefinition, force: Boolean): Future[DeploymentPlan]
  def delete(id: AbsolutePathId, force: Boolean): Future[DeploymentPlan]
  def versions(id: AbsolutePathId): Source[Timestamp, NotUsed]
  def version(id: AbsolutePathId, version: Timestamp): Future[Option[PodDefinition]]
}
