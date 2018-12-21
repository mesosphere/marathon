package mesosphere.raml.ir

import mesosphere.raml.backend
import mesosphere.raml.backend._

import treehugger.forest._
import definitions._
import treehuggerDSL._

case class EnumT(name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
  val sortedValues = values.toVector.sorted
  override def toString: String = s"Enum($name, $values)"

  // TODO(karsten): This is actually part of the back end.
  override def toTree(): Seq[Tree] = {
    val baseTrait = TRAITDEF(name) withParents("Product", "Serializable", "RamlGenerated") withFlags Flags.SEALED := BLOCK(
      VAL("value", StringClass),
      DEF("toString", StringClass) withFlags Flags.OVERRIDE := REF("value")
    )

    val enumObjects = sortedValues.map { enumValue =>
      CASEOBJECTDEF(underscoreToCamel(camelify(enumValue))) withParents name := BLOCK(
        VAL("value") := LIT(enumValue)
      )
    }

    val patternMatches = sortedValues.map { enumValue =>
      CASE(LIT(enumValue.toLowerCase)) ==> REF(underscoreToCamel(camelify(enumValue)))
    }

    val playWildcard = CASE(WILDCARD) ==>
      (REF(PlayJsError) APPLY (REF(PlayValidationError) APPLY(LIT("error.unknown.enum.literal"), LIT(s"$name (${sortedValues.mkString(", ")})"))))
    val playPatternMatches = sortedValues.map { enumValue =>
      CASE(LIT(enumValue.toLowerCase)) ==> (REF(PlayJsSuccess) APPLY REF(underscoreToCamel(camelify(enumValue))))
    }

    val playJsonFormat = (OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT) := BLOCK(
      DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := {
        REF("json") MATCH(
          CASE(REF(PlayJsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH (playPatternMatches ++ Vector(playWildcard))),
          playWildcard)
      },
      DEF("writes", PlayJsValue) withParams PARAM("o", name) := {
        REF(PlayJsString) APPLY (REF("o") DOT "value")
      }
    )

    val obj = OBJECTDEF(name) := BLOCK(
      enumObjects ++ Seq(
        playJsonFormat,
        VAL("StringToValue") withType(TYPE_MAP(StringClass, name)) withFlags(Flags.PRIVATE) := REF("Map") APPLY(sortedValues.map { enumValue =>
          TUPLE(LIT(enumValue), REF(underscoreToCamel(camelify(enumValue))))
        }),
        DEF("all", backend.IterableClass TYPE_OF name) := REF("StringToValue") DOT "values",
        DEF("fromString", TYPE_OPTION(name)) withParams(PARAM("v", StringClass)) := REF("StringToValue") DOT "get" APPLY(REF("v"))
      ) ++ default.map { defaultValue =>
        VAL("DefaultValue") withType(name) := REF(underscoreToCamel(camelify(defaultValue)))
      }
    )
    Seq(baseTrait.withDoc(comments), obj)
  }
}
