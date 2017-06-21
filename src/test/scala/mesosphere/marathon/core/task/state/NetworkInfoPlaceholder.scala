package mesosphere.marathon
package core.task.state

import org.apache.mesos.Protos.NetworkInfo.IPAddress
import NetworkInfoTestDefaults._

/** NetworkInfo to use in tests if no specific values are required */
object NetworkInfoPlaceholder {
  def apply(
    hostName: String = defaultHostName,
    hostPorts: Seq[Int] = defaultHostPorts,
    ipAddresses: Seq[IPAddress] = defaultIpAddresses): NetworkInfo = new NetworkInfo(hostName, hostPorts, ipAddresses)
}

/** Defaults for NetworkInfo to use in tests */
object NetworkInfoTestDefaults {
  val defaultHostName: String = "host.some"
  val defaultHostPorts: Seq[Int] = Nil
  val defaultIpAddresses: Seq[IPAddress] = Nil
}
