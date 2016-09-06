package mesosphere.marathon.core.pod

import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq

trait PodManager {
  def create(p: PodDefinition, force: Boolean): DeploymentPlan
  def findAll(s: (PodDefinition) => Boolean): Seq[PodDefinition]
  def find(id: PathId): PodDefinition
  def update(p: PodDefinition, force: Boolean): DeploymentPlan
  def delete(id: PathId, force: Boolean): DeploymentPlan
  def status(id: PathId): PodStatus
}
