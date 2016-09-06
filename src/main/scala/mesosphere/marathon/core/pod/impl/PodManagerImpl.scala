package mesosphere.marathon.core.pod.impl

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.PathId
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq

case class PodManagerImpl(groupManager: GroupManager) extends PodManager {
  def create(p: PodDefinition, force: Boolean): DeploymentPlan = ???
  def findAll(s: (PodDefinition) => Boolean): Seq[PodDefinition] = ???
  def find(id: PathId): PodDefinition = ???
  def update(p: PodDefinition, force: Boolean): DeploymentPlan = ???
  def delete(id: PathId, force: Boolean): DeploymentPlan = ???
  def status(id: PathId): PodStatus = ???
}
