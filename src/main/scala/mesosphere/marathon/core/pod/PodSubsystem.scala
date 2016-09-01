package mesosphere.marathon.core.pod

import mesosphere.marathon.api.v2.PodsResource
import mesosphere.marathon.raml.PodStatus

class PodSubsystem extends PodsResource.System {

  def create(p: PodDefinition, force: Boolean): (PodDefinition, Option[String]) =
    throw new NotImplementedError()

  def findAll(s: PodsResource.Selector): Iterable[PodDefinition] =
    throw new NotImplementedError()

  def find(id: String): PodDefinition =
    throw new NotImplementedError()

  def update(p: PodDefinition, force: Boolean): (PodDefinition, Option[String]) =
    throw new NotImplementedError()

  def delete(id: String, force: Boolean): (PodDefinition, Option[String]) =
    throw new NotImplementedError()

  def examine(id: String): PodStatus =
    throw new NotImplementedError()
}
