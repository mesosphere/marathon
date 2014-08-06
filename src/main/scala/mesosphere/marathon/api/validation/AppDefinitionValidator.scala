package mesosphere.marathon.api.validation

import javax.validation.{ ConstraintValidatorContext, ConstraintValidator }

import mesosphere.marathon.EmptyContainerInfo
import mesosphere.marathon.state.AppDefinition

class AppDefinitionValidator extends ConstraintValidator[ValidAppDefinition, AppDefinition] {
  override def initialize(constraintAnnotation: ValidAppDefinition): Unit = {}

  override def isValid(value: AppDefinition, context: ConstraintValidatorContext): Boolean =
    value.cmd.nonEmpty || value.container.exists(_ != EmptyContainerInfo)
}
