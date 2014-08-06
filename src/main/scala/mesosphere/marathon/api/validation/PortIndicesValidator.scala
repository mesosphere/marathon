package mesosphere.marathon.api.validation

import mesosphere.marathon.state.AppDefinition
import javax.validation.{ ConstraintValidator, ConstraintValidatorContext }

class PortIndicesValidator
    extends ConstraintValidator[PortIndices, AppDefinition] {

  def initialize(annotation: PortIndices): Unit = {}

  def isValid(app: AppDefinition, context: ConstraintValidatorContext): Boolean =
    app.portIndicesAreValid()

}
