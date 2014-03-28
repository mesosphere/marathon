package mesosphere.marathon.api.validation

import mesosphere.marathon.api.v1.AppDefinition
import javax.validation.{ConstraintValidator, ConstraintValidatorContext}

class PortIndicesValidator
  extends ConstraintValidator[PortIndices, AppDefinition] {

  def initialize(annotation: PortIndices): Unit = {}

  def isValid(app: AppDefinition, context: ConstraintValidatorContext): Boolean =
    app.portIndicesAreValid()

}
