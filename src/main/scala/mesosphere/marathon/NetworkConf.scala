package mesosphere.marathon

import org.rogach.scallop.ScallopConf

import scala.util.matching.Regex

trait NetworkConf extends ScallopConf {
  lazy val mesosBridgeName = opt[String](
    "mesos_bridge_name",
    descr = "The name of the Mesos CNI network used by MESOS-type containers configured to use bridged networking",
    noshort = true,
    default = Some(raml.Networks.DefaultMesosBridgeName))

  validate (mesosBridgeName) { str =>
    import NetworkConf._
    if (NetworkNamePattern.pattern.matcher(str).matches())
      Right(Unit)
    else
      Left(NetworkNameFailedMessage)
  }

  lazy val defaultNetworkName = opt[String](
    "default_network_name",
    descr = "Network name, injected into applications' container-mode network{} specs that do not define their own name.",
    noshort = true
  )

}

object NetworkConf {
  // Note - this pattern should match types.Name in stringTypes.raml
  val NetworkNamePattern: Regex = raml.Network.ConstraintNamePattern
  val NetworkNameFailedMessage: String = s"mesos_bridge_name must match pattern $NetworkNamePattern"
}
