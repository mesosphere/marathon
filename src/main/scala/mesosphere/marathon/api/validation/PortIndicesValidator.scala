package mesosphere.marathon.api.validation

import mesosphere.marathon.api.v2.json.V2AppDefinition
import javax.validation.{ ConstraintValidator, ConstraintValidatorContext }

class PortIndicesValidator
    extends ConstraintValidator[PortIndices, V2AppDefinition] {

  def initialize(annotation: PortIndices): Unit = {}

  def isValid(app: V2AppDefinition, context: ConstraintValidatorContext): Boolean =
    app.portIndicesAreValid()

}
