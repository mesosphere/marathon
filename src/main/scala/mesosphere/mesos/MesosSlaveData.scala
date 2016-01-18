package mesosphere.mesos

case class MesosSlaveData(id: String, pid: String, active: Boolean, hostname: String, attributes: Map[String, String])

