package mesosphere.marathon.api

import org.hibernate.validator.constraints.NotEmpty
import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import javax.validation.constraints.Pattern
import scala.annotation.target.field

/**
 * Provides type aliases for constraint annotations that target fields
 * associated with Scala class constructor arguments.
 */
object FieldConstraints {
  type FieldNotEmpty = NotEmpty @field
  type FieldPattern = Pattern @field
  type FieldJsonInclude = JsonInclude @field
  type FieldJsonDeserialize = JsonDeserialize @field
  type FieldJsonProperty = JsonProperty @field
}
