package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._

import scala.collection.immutable.Seq

case class PortDefinition(port: Int,
                          protocol: String = "tcp",
                          name: Option[String] = None,
                          labels: Map[String, String] = Map.empty[String, String])

object PortDefinition {
  implicit val portDefinitionValidator = validator[PortDefinition] { portDefinition =>
    portDefinition.protocol is oneOf(DiscoveryInfo.Port.AllowedProtocols)
    portDefinition.port should be >= 0
    portDefinition.name is optional(matchRegexFully(PortAssignment.PortNamePattern))
  }
}

object PortDefinitions {
  def apply(ports: Int*): Seq[PortDefinition] = {
    ports.map(PortDefinition.apply(_)).toIndexedSeq
  }

  implicit val portDefinitionsValidator: Validator[Seq[PortDefinition]] = validator[Seq[PortDefinition]] {
    portDefinitions =>
      portDefinitions is every(valid)
      portDefinitions is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
      portDefinitions is elementsAreUniqueBy(_.port, "Ports must be unique.",
        filter = { port: Int => port != AppDefinition.RandomPortValue })
  }
}
