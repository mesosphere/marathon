package mesosphere.raml.ir

import mesosphere.raml.backend.{PlayReads, camelify, scalaFieldName, underscoreToCamel}
import treehugger.forest._
import treehuggerDSL._

case class FieldT(
    rawName: String,
    `type`: Type,
    comments: Seq[String],
    constraints: Seq[ConstraintT[_]],
    required: Boolean,
    default: Option[String],
    repeated: Boolean = false,
    forceOptional: Boolean = false,
    omitEmpty: Boolean = false
) {

  val name = scalaFieldName(rawName)
  override def toString: String = s"$name: ${`type`}"

  /**
    * Determine if this field should be wrapped in an Option or not
    */
  lazy val isOptionType: Boolean = {
    if ((required || default.isDefined) && !forceOptional) {
      false
    } else {
      if (repeated && !forceOptional) {
        false
      } else {
        true
      }
    }
  }

  /**
    * Determine if this field is a container type, i.e. a set or a map
    */
  lazy val isContainerType: Boolean = {
    if ((required || default.isDefined) && !forceOptional) {
      false
    } else {
      if (repeated && !forceOptional) {
        val typeName = `type`.toString()
        if (typeName.startsWith("Map")) {
          true
        } else {
          if (typeName.startsWith("Set")) {
            true
          } else {
            false
          }
        }
      } else {
        false
      }
    }
  }

  lazy val paramTypeValue: Option[(Type, Tree)] = {
    if ((required || default.isDefined) && !forceOptional) {
      defaultValue.map { d => `type` -> d }
    } else {
      Option(
        if (repeated && !forceOptional) {
          val typeName = `type`.toString()
          if (typeName.startsWith("Map")) {
            `type` -> (REF("Map") DOT "empty")
          } else {
            if (typeName.startsWith("Set")) {
              `type` -> (REF("Set") DOT "empty")
            } else {
              `type` -> NIL
            }
          }
        } else {
          TYPE_OPTION(`type`) -> NONE
        }
      )
    }
  }

  lazy val comment: String = if (comments.nonEmpty) {
    val lines = comments.flatMap(_.lines)
    s"@param $name ${lines.head} ${if (lines.tail.nonEmpty) "\n  " else ""}${lines.tail.mkString("\n  ")}"
  } else {
    ""
  }

  val defaultValue: Option[Tree] = default.map { d =>
    `type`.toString() match {
      case "Byte" => LIT(d.toByte)
      case "Short" => LIT(d.toShort)
      case "Int" => LIT(d.toInt)
      case "Long" => LIT(d.toLong)
      case "Float" => LIT(d.toFloat)
      case "Double" => LIT(d.toDouble)
      case "Boolean" => LIT(d.toBoolean)
      case "String" => LIT(d)
      // hopefully this is actually an enum
      case _ => (`type` DOT underscoreToCamel(camelify(d))).tree
    }
  }

  val playReader = {
    // required fields never have defaults
    if (required && !forceOptional) {
      TUPLE(REF("__") DOT "\\" APPLY LIT(rawName)) DOT "read" APPLYTYPE `type`
    } else if (repeated && !forceOptional) {
      TUPLE(REF("__") DOT "\\" APPLY LIT(rawName)) DOT "read" APPLYTYPE `type` DOT "orElse" APPLY (REF(
        PlayReads
      ) DOT "pure" APPLY (`type` APPLY ()))
    } else {
      if (defaultValue.isDefined && !forceOptional) {
        TUPLE((REF("__") DOT "\\" APPLY LIT(rawName)) DOT "read" APPLYTYPE `type`) DOT "orElse" APPLY (REF(
          PlayReads
        ) DOT "pure" APPLY defaultValue.get)
      } else {
        TUPLE((REF("__") DOT "\\" APPLY LIT(rawName)) DOT "readNullable" APPLYTYPE `type`)
      }
    }
  }
}
