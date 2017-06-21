package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._

import scala.collection.immutable.Seq

case class PortDefinition(
  port: Int,
  protocol: String = "tcp",
  name: Option[String] = None,
  labels: Map[String, String] = Map.empty[String, String])

object PortDefinition {
  implicit val portDefinitionValidator = validator[PortDefinition] { portDefinition =>
    portDefinition.protocol.split(',').toIterable is every(oneOf(PortDefinitions.AllowedProtocols))
    portDefinition.port should be >= 0
  }
}

object PortDefinitions {
  val AllowedProtocols: Set[String] = Set("tcp", "udp")

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
