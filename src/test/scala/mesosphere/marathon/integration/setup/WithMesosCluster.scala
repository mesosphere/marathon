package mesosphere.marathon.integration.setup

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.{ ConfigMap, Suite }
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

trait WithMesosCluster extends SingleMarathonIntegrationTest { self: Suite =>
  import WithMesosCluster.log

  override protected def createConfig(configMap: ConfigMap): IntegrationTestConfig = {
    val cfg = super.createConfig(configMap)
    cfg.copy(master = s"zk://${cfg.zkHostAndPort}/mesos")
  }

  val mesosWorkDir = "/tmp/marathon-itest-mesos"
  val master1: String = "master1"
  val master2: String = "master2"
  val slave1: String = "slave1"
  val slave2: String = "slave2"

  private val ports: TrieMap[String, Int] = TrieMap.empty
  private val portIt = Range(50050, Int.MaxValue).iterator
  def processPort(name: String): Int = ports.getOrElseUpdate(name, portIt.next())

  /**
    * TODO: implement me if we need this for mesos cluster support
    */
  override def waitForCleanSlateInMesos(): Boolean = {
    log.warn("Will NOT wait for clean Mesos slate; mesos cluster support is not yet implemented.")
    true
  }

  def startMaster(name: String, wipe: Boolean = true): Unit = {
    val masterArgs = Seq("--slave_ping_timeout=1secs", "--max_slave_ping_timeouts=4", "--quorum=1")
    ProcessKeeper.startMesos(name, s"$mesosWorkDir/$name", Seq("mesos", "master", s"--zk=zk://${config.zkHostAndPort}/mesos", s"--work_dir=$mesosWorkDir/$name", s"--port=${processPort(name)}") ++ masterArgs, "new candidate", wipe)
  }

  def startSlave(name: String, wipe: Boolean = true): Unit = {
    ProcessKeeper.startMesos(name, s"$mesosWorkDir/$name", Seq("mesos", "slave", "--no-systemd_enable_support", s"--master=zk://${config.zkHostAndPort}/mesos", s"--hostname=$name", s"--work_dir=$mesosWorkDir/$name", s"--port=${processPort(name)}"), "registered with master", wipe)
  }

  def stopMesos(name: String): Unit = ProcessKeeper.stopProcess(name)

  override protected def startMesos(): Unit = {
    FileUtils.deleteDirectory(new File(mesosWorkDir))
    startMaster(master1)
    startSlave(slave1)
  }
}

object WithMesosCluster {
  val log = LoggerFactory.getLogger(getClass)
}