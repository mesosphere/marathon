package mesosphere.marathon.api.validation

import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import javax.validation.{ ConstraintValidator, ConstraintValidatorContext }

/**
  * FIXME: This is not called when an V2AppDefinition is called because the recursion apparently doesn't work
  * with Scala Seq.
  *
  * We cannot re-enable it because it would break backwards compatibility in weird ways. If users hard already
  * one invalid app definition, each deployment would cause a complete revalidation of the root group
  * including the invalid one. Until the user changed all invalid apps, the user would get weird validation
  * errors for every deployment potentially unrelated to the deployed apps.
  */
class HealthCheckValidator
    extends ConstraintValidator[ValidHealthCheck, HealthCheck] {

  def initialize(annotation: ValidHealthCheck): Unit = {}

  def isValid(hc: HealthCheck, context: ConstraintValidatorContext): Boolean = {
    def eitherPortIndexOrPort: Boolean = hc.portIndex.isDefined ^ hc.port.isDefined

    val hasCommand = hc.command.isDefined
    val hasPath = hc.path.isDefined
    hc.protocol match {
      case Protocol.COMMAND => hasCommand && !hasPath && hc.port.isEmpty
      case Protocol.HTTP    => !hasCommand && eitherPortIndexOrPort
      case Protocol.TCP     => !hasCommand && !hasPath && eitherPortIndexOrPort
      case _                => true
    }
  }
}
