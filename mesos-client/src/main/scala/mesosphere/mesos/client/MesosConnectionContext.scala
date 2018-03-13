package mesosphere.mesos.client

import java.net.URI

import mesosphere.mesos.conf.MesosClientConf
import org.apache.mesos.v1.mesos.FrameworkID

case class MesosConnectionContext(
    url: URI,
    streamId: Option[String],
    frameworkId: Option[FrameworkID]) {
  def host = url.getHost
  def port = url.getPort
}

object MesosConnectionContext {
  def apply(conf: MesosClientConf): MesosConnectionContext = MesosConnectionContext(
    new java.net.URI(s"http://${conf.master}"),
    None,
    None)
}