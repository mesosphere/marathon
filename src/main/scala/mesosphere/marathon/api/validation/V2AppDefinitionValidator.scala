package mesosphere.marathon.api.validation

import javax.validation.{ ConstraintValidatorContext, ConstraintValidator }

import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.state.Container

class V2AppDefinitionValidator extends ConstraintValidator[ValidV2AppDefinition, V2AppDefinition] {
  override def initialize(constraintAnnotation: ValidV2AppDefinition): Unit = {}

  override def isValid(
    value: V2AppDefinition,
    context: ConstraintValidatorContext): Boolean = {
    val cmd = value.cmd.nonEmpty
    val args = value.args.nonEmpty
    val container = value.container.exists(_ != Container.Empty)
    (cmd ^ args) || (!(cmd || args) && container)
  }

}
