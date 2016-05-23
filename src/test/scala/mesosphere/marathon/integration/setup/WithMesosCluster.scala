package mesosphere.marathon.integration.setup

import org.scalatest.{ ConfigMap, Suite }

import scala.collection.concurrent.TrieMap

trait WithMesosCluster extends SingleMarathonIntegrationTest { self: Suite =>

  override protected def createConfig(configMap: ConfigMap): IntegrationTestConfig = {
    val cfg = super.createConfig(configMap)
    cfg.copy(master = s"zk://${cfg.zkHostAndPort}/mesos")
  }

  val master1: String = "master1"
  val master2: String = "master2"
  val slave1: String = "slave1"
  val slave2: String = "slave2"

  private val ports: TrieMap[String, Int] = TrieMap.empty
  private val portIt = Range(50050, Int.MaxValue).iterator
  def processPort(name: String): Int = ports.getOrElseUpdate(name, portIt.next())

  def startMaster(name: String, wipe: Boolean = true): Unit = {
    val dir = "/tmp/marathon-itest-mesos"
    val masterArgs = Seq("--slave_ping_timeout=1secs", "--max_slave_ping_timeouts=4", "--quorum=1")
    ProcessKeeper.startMesos(name, s"$dir/$name", Seq("mesos", "master", s"--zk=zk://${config.zkHostAndPort}/mesos", s"--work_dir=$dir/$name", s"--port=${processPort(name)}") ++ masterArgs, "new candidate", wipe)
  }

  def startSlave(name: String, wipe: Boolean = true): Unit = {
    val dir = "/tmp/marathon-itest-mesos"
    ProcessKeeper.startMesos(name, s"$dir/$name", Seq("mesos", "slave", s"--master=zk://${config.zkHostAndPort}/mesos", s"--work_dir=$dir/$name", s"--port=${processPort(name)}"), "registered with master", wipe)
  }

  def stopMesos(name: String): Unit = ProcessKeeper.stopProcess(name)

  override protected def startMesos(): Unit = {
    startMaster(master1)
    startMaster(master2)
    startSlave(slave1)
    startSlave(slave2)
  }
}
