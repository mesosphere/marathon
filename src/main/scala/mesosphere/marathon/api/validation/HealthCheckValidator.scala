package mesosphere.marathon.api.validation

import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import javax.validation.{ ConstraintValidator, ConstraintValidatorContext }

class HealthCheckValidator
    extends ConstraintValidator[ValidHealthCheck, HealthCheck] {

  def initialize(annotation: ValidHealthCheck): Unit = {}

  def isValid(hc: HealthCheck, context: ConstraintValidatorContext): Boolean = {
    val hasCommand = hc.command.isDefined
    val hasPath = hc.path.isDefined
    hc.protocol match {
      case Protocol.COMMAND => hasCommand && !hasPath
      case Protocol.HTTP    => !hasCommand
      case Protocol.TCP     => !hasCommand && !hasPath
      case _                => true
    }
  }

}
