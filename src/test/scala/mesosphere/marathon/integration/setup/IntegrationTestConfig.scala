package mesosphere.marathon.integration.setup

import java.io.File

import org.scalatest.ConfigMap

import scala.util.Try

/**
  * Configuration used in integration test.
  * Pass parameter from scala test by command line and create via ConfigMap.
  * mvn: pass this parameter via command line -DtestConfig="cwd=/tmp,zk=zk://somehost"
  */
case class IntegrationTestConfig(

    //current working directory for all processes to span: defaults to .
    cwd: String,

    //zookeeper port. defaults to 2183
    zkPort: Int,

    //url to mesos master. defaults to local
    master: String,

    //mesosLib: path to the native mesos lib. Defaults to /usr/local/lib/libmesos.dylib
    mesosLib: String,

    //for single marathon tests, the marathon port to use.
    singleMarathonPort: Int,

    //the port for the local http interface which receives all callbacks from marathon.
    //Defaults dynamically to a port [11211-11311]
    httpPort: Int) {

  private val zkURLPattern = """^zk://([A-z0-9-.]+):(\d+)(.+)$""".r

  def zkHostAndPort = s"127.0.0.1:$zkPort"
  def zkPath = "/test"
  def zk = s"zk://127.0.0.1:$zkPort$zkPath"
}

object IntegrationTestConfig {
  /**
    * Tries to find the native mesos library automatically on Linux and Mac OS X.
    *
    * @return the detected location of the native mesos library.
    */
  private[this] def defaultMesosLibConfig: String = {
    val javaLibraryPath = sys.props.getOrElse("java.library.path", "/usr/local/lib:/usr/lib:/usr/lib64")
    val dirs = javaLibraryPath.split(':').toStream #::: Stream("/usr/local/lib", "/usr/lib", "/usr/lib64")
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
    val zkPort = int("zkPort", 2183)
    val master = string("master", "local")
    val mesosLib = string("mesosLib", defaultMesosLibConfig)
    val httpPort = int("httpPort", 11211 + (math.random * 100).toInt)
    val singleMarathonPort = int("singleMarathonPort", 8080 + (math.random * 100).toInt)
    IntegrationTestConfig(cwd, zkPort, master, mesosLib, singleMarathonPort, httpPort)
  }
}

