package mesosphere.mesos.protos

import org.apache.mesos.Protos

case class ResourceProviderID(value: String)

object ResourceProviderID {
  def fromResourceProto(resource: Protos.Resource): Option[ResourceProviderID] = {
    if (resource.hasProviderId && resource.getProviderId.hasValue) {
      Some(ResourceProviderID(resource.getProviderId.getValue))
    } else {
      None
    }
  }
}
