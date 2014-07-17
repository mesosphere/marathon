package mesosphere.marathon.integration.setup

import org.scalatest.ConfigMap

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

    //the port for the local http interface. Defaults dynamically to a port [11211-11311]
    httpPort: Int) {

  private val zkURLPattern = """^zk://([A-z0-9-.]+):(\d+)(.+)$""".r

  def zkHostAndPort = s"127.0.0.1:$zkPort"
  def zkPath = "/test"
  def zk = s"zk://127.0.0.1:$zkPort$zkPath"
}

object IntegrationTestConfig {
  def apply(config: ConfigMap): IntegrationTestConfig = {
    def string(name: String, default: String) = config.getOptional[String](name).getOrElse(default)
    def int(name: String, default: Int) = config.getOptional[String](name).fold(default)(_.toInt)
    val cwd = string("cwd", ".")
    val zkPort = int("zkPort", 2183)
    val master = string("master", "local")
    val mesosLib = string("mesosLib", "/usr/local/lib/libmesos.dylib")
    val httpPort = int("httpPort", 11211 + (math.random * 100).toInt)
    val singleMarathonPort = int("singleMarathonPort", 8080 + (math.random * 100).toInt)
    IntegrationTestConfig(cwd, zkPort, master, mesosLib, singleMarathonPort, httpPort)
  }
}

