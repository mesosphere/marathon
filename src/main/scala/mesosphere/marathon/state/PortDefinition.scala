package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.{ Failure, RuleViolation, Success }
import mesosphere.marathon.api.v2.Validation._
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

case class PortDefinition(port: Int,
                          protocol: String = "tcp",
                          name: Option[String] = None,
                          labels: Map[String, String] = Map.empty[String, String])

object PortDefinition {
  implicit val portDefinitionValidator = validator[PortDefinition] { portDefinition =>
    portDefinition.protocol is oneOf(DiscoveryInfo.Port.AllowedProtocols)
    portDefinition.port should be >= 0
  }
}

object PortDefinitions {
  def apply(ports: Int*): Seq[PortDefinition] = {
    ports.map(PortDefinition.apply(_)).toIndexedSeq
  }

  private[this] def uniquePorts: Validator[Seq[PortDefinition]] = new Validator[Seq[PortDefinition]] {
    override def apply(portDefinitions: Seq[PortDefinition]): Result = {
      val nonRandomPorts = portDefinitions.map(_.port).filterNot(_ == AppDefinition.RandomPortValue)

      if (nonRandomPorts.size == nonRandomPorts.distinct.size) Success
      else Failure(Set(RuleViolation("portDefinitions", "Ports must be unique.", None)))
    }
  }

  private[this] def uniquePortNames: Validator[Seq[PortDefinition]] = new Validator[Seq[PortDefinition]] {
    override def apply(portDefinitions: Seq[PortDefinition]): Result = {
      val portNames = portDefinitions.flatMap(_.name)
      if (portNames.size == portNames.distinct.size) Success
      else Failure(Set(RuleViolation("portDefinitions", "Port names must be unique.", None)))
    }
  }

  implicit val portDefinitionsValidator: Validator[Seq[PortDefinition]] = validator[Seq[PortDefinition]] {
    portDefinitions => portDefinitions is every(valid) and uniquePorts and uniquePortNames
  }
}
