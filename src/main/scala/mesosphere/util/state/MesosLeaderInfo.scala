package mesosphere.util.state

import org.apache.mesos.Protos.MasterInfo

/**
  * Utility class for keeping track of a Mesos Leader
  */
trait MesosLeaderInfo {

  /**
    * Return information about current Mesos master URL
    * @return String in format "protocol://host:port/" (e.g. "http://mesos.vm:5050/")
    */
  def currentLeaderUrl: Option[String]

  def onNewMasterInfo(master: MasterInfo): Unit
}

case class ConstMesosLeaderInfo(currentLeaderUrl: Option[String]) extends MesosLeaderInfo {

  override def onNewMasterInfo(ignored: MasterInfo): Unit = {}
}

class MutableMesosLeaderInfo extends MesosLeaderInfo {

  @volatile private[this] var leaderUrl: Option[String] = None

  override def currentLeaderUrl: Option[String] = {
    leaderUrl
  }

  override def onNewMasterInfo(master: MasterInfo): Unit = {
    leaderUrl = Some(s"http://${master.getHostname}:${master.getPort}/")
  }
}

