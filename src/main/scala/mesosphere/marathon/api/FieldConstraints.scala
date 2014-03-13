package mesosphere.marathon.api

import org.hibernate.validator.constraints.NotEmpty
import com.fasterxml.jackson.annotation.{
  JsonInclude,
  JsonProperty,
  JsonValue
}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import javax.validation.constraints.Pattern
import scala.annotation.target.field
import com.fasterxml.jackson.module.scala.JsonScalaEnumeration

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
  type FieldJsonValue = JsonValue @field
  type FieldJsonScalaEnumeration = JsonScalaEnumeration @field
}
