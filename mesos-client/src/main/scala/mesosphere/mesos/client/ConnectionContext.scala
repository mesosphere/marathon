package mesosphere.mesos.client

import mesosphere.mesos.conf.MesosConf
import org.apache.mesos.v1.mesos.FrameworkID

case class ConnectionContext(host: String,
                             port: Int,
                             mesosStreamId: Option[String],
                             frameworkId: Option[FrameworkID]) {
  def url = s"$host:$port"
}


object ConnectionContext {
  def apply(conf: MesosConf): ConnectionContext = ConnectionContext(
    conf.mesosMasterHost,
    conf.mesosMasterPort,
    None, None)
}