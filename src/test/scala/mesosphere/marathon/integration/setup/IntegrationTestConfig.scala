package mesosphere.marathon.integration.setup

import java.io.File

import mesosphere.marathon.state.PathId
import mesosphere.util.PortAllocator
import org.scalatest.ConfigMap

/**
  * Configuration used in integration test.
  * Pass parameter from scala test by command line and create via ConfigMap.
  *
  * sbt console examples:
  *
  * run tests against an already started marathon on port 8080:
  *  integration:testOnly **DeployIntegration* -- -DmarathonHost=localhost -DmarathonPort=8080 -DuseExternalSetup=true -DzkPort=2181
  *
  * run tests against a separate mesos/zookeeper/marathon setup started by the tests:
  *  integration:testOnly **DeployIntegration*
  *
  * See [[IntegrationTestConfig.apply]] for possible parameters.
  */
case class IntegrationTestConfig(

    //current working directory for all processes to span: defaults to .
    cwd: String,

    //if true, use external zookeeper and marathon instead of starting local instances.
    useExternalSetup: Boolean,

    //zookeeper host. defaults to localhost
    //unused for useExternalSetup
    zkHost: String,

    //zookeeper port. defaults to 2183
    //unused for useExternalSetup
    zkPort: Int,

    //zookeeper digest authinfo. defaults to None
    zkCredentials: Option[String],

    //url to mesos master. defaults to local. Unused for useExternalSetup.
    // unused for useExternalSetup
    master: String,

    //mesosLib: path to the native mesos lib. Defaults to /usr/local/lib/libmesos.dylib
    mesosLib: String,

    mesosPort: Int,

    //the marathon host to use.
    marathonHost: String,

    //the base marathon port to use
    marathonBasePort: Int,

    //perform test inside of this group
    marathonGroup: PathId,

    //the port for the local http interface which receives all callbacks from marathon.
    //Defaults dynamically to a port [11211-11311]
    httpPort: Int,

    //the number of nodes to start. Used by MarathonClusterIntegrationTest
    clusterSize: Int,

    //List of the ports to use in the cluster formed by MarathonClusterIntegrationTest
    marathonPorts: Seq[Int]) {

  private val zkURLPattern = """^zk://([A-z0-9-.]+):(\d+)(.+)$""".r

  def zkHostAndPort: String = s"127.0.0.1:$zkPort"
  def zkPath: String = "/marathon-itest"
  def zk: String = zkCredentials match {
    case None => s"zk://$zkHostAndPort$zkPath"
    case Some(userPassword) => s"zk://$userPassword@$zkHostAndPort$zkPath"
  }

  def marathonUrls: Seq[String] = marathonPorts.map(port => s"http://$marathonHost:$port")
  def marathonUrl: String = marathonUrls.head
}

object IntegrationTestConfig {
  /**
    * Tries to find the native mesos library automatically on Linux and Mac OS X.
    *
    * @return the detected location of the native mesos library.
    */
  private[this] def defaultMesosLibConfig: String = {
    val javaLibraryPath = sys.props.getOrElse("java.library.path", "/usr/local/lib:/usr/lib:/usr/lib64")
    val mesos_dir = sys.env.get("MESOS_DIR").map(_ + "/lib").toStream
    val dirs =
      javaLibraryPath.split(':').toStream #::: Stream("/usr/local/lib", "/usr/lib", "/usr/lib64") #::: mesos_dir
    val libCandidates = dirs.flatMap { (libDir: String) =>
      Stream(
        new File(libDir, "libmesos.dylib"),
        new File(libDir, "libmesos.so")
      )
    }

    libCandidates.find(_.exists()).map(_.getAbsolutePath).getOrElse {
      throw new RuntimeException(s"No mesos library found. Candidates: ${libCandidates.mkString(", ")}")
    }
  }

  def apply(config: ConfigMap): IntegrationTestConfig = {
    def string(name: String, default: => String) = config.getOptional[String](name).getOrElse(default)
    def int(name: String, default: => Int) = config.getOptional[String](name).fold(default)(_.toInt)
    val cwd = string("cwd", ".")
    val useExternalSetup = string("useExternalSetup", "false").toBoolean

    def unusedForExternalSetup(block: => String): String = {
      if (useExternalSetup) {
        "UNUSED FOR EXTERNAL SETUP"
      } else {
        block
      }
    }

    val zkHost = string("zkHost", unusedForExternalSetup("localhost"))
    val zkPort = int("zkPort", PortAllocator.ephemeralPort())
    val zkCredentials = config.getOptional[String]("zkCredentials")
    val mesosPort = int("mesosPort", PortAllocator.ephemeralPort())
    val master = string("master", unusedForExternalSetup(s"127.0.0.1:$mesosPort"))
    val mesosLib = string("mesosLib", unusedForExternalSetup(defaultMesosLibConfig))
    val httpPort = int("httpPort", PortAllocator.ephemeralPort())
    val marathonHost = string("marathonHost", "localhost")
    val marathonBasePort = int("marathonPort", PortAllocator.ephemeralPort())
    val clusterSize = int("clusterSize", 3)
    val marathonPorts = 0.until(clusterSize).map(_ => PortAllocator.ephemeralPort())
    val marathonGroup = PathId(string("marathonGroup", "/marathon_integration_test"))

    IntegrationTestConfig(
      cwd,
      useExternalSetup,
      zkHost, zkPort, zkCredentials,
      master, mesosLib, mesosPort,
      marathonHost, marathonBasePort, marathonGroup,
      httpPort,
      clusterSize,
      marathonBasePort +: marathonPorts)
  }
}

