package mesosphere.marathon
package core.task.state

import org.apache.mesos.Protos.NetworkInfo.IPAddress
import AgentTestDefaults._
import mesosphere.marathon.core.instance.Instance.AgentInfo
import org.apache.mesos

/** NetworkInfo to use in tests if no specific values are required */
object NetworkInfoPlaceholder {
  def apply(
    hostName: String = defaultHostName,
    hostPorts: Seq[Int] = defaultHostPorts,
    ipAddresses: Seq[IPAddress] = defaultIpAddresses): NetworkInfo = new NetworkInfo(hostName, hostPorts, ipAddresses)
}

/** Defaults for NetworkInfo to use in tests */
object AgentTestDefaults {
  val defaultHostName: String = "host.some"
  val defaultAgentId: String = "agent-1"
  val defaultHostPorts: Seq[Int] = Nil
  val defaultIpAddresses: Seq[IPAddress] = Nil
}

object AgentInfoPlaceholder {
  def apply(
    host: String = defaultHostName,
    agentId: Option[String] = Some(defaultAgentId),
    attributes: Seq[mesos.Protos.Attribute] = Seq.empty
  ): AgentInfo = AgentInfo(host, agentId, attributes)
}