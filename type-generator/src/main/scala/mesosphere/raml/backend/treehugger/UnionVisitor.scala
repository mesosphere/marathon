package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.{PlayJson, PlayJsNumber, PlayJsValue, PlayJsString, PLAY_JSON_FORMAT, PLAY_JSON_RESULT}

import mesosphere.raml.ir.{StringT, UnionT, NumberT}
import treehugger.forest._
import definitions._
import treehuggerDSL._

object UnionVisitor {

  def visit(unionT: UnionT): GeneratedFile = {
    val UnionT(name, childTypes, comments) = unionT

    val base = (TRAITDEF(name) withParents ("RamlGenerated", "Product", "Serializable")).tree.withDoc(comments)
    val childJson: Seq[GenericApply] = childTypes.map { child =>
      REF("json") DOT s"validate" APPLYTYPE (child.name)
    }

    val obj = OBJECTDEF(name) := BLOCK(
      OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
        DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := BLOCK(
          childJson.reduce((acc, next) => acc DOT "orElse" APPLY next)
        ),
        DEF("writes", PlayJsValue) withParams PARAM("o", name) := BLOCK(
          REF("o") MATCH
            childTypes.map { child =>
              CASE(REF(s"f:${child.name}")) ==> (REF(PlayJson) DOT "toJson" APPLY REF("f") APPLY (REF(child.name) DOT "playJsonFormat"))
            }
        )
      )
    )
    val children: Seq[GeneratedObject] = childTypes.flatMap {
      case s: NumberT => {
        val caseClass =
          CASECLASSDEF(s.name) withParents name withParams s.defaultValue.fold(PARAM("value", DoubleClass).tree) { defaultValue =>
            PARAM("value", DoubleClass) := LIT(defaultValue.toDouble)
          }

        val objectDef =
          OBJECTDEF(s.name) := BLOCK(
            Seq(
              OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(s.name) withFlags Flags.IMPLICIT := BLOCK(
                DEF("reads", PLAY_JSON_RESULT(s.name)) withParams PARAM("json", PlayJsValue) := BLOCK(
                  REF("json") DOT "validate" APPLYTYPE DoubleClass DOT "map" APPLY (REF(s.name) DOT "apply")
                ),
                DEF("writes", PlayJsValue) withParams PARAM("o", s.name) := BLOCK(
                  REF(PlayJsNumber) APPLY (REF("o") DOT "value")
                )
              )
            ) ++ s.defaultValue.map { defaultValue =>
              VAL("DefaultValue") withType (s.name) := REF(s.name) APPLY ()
            }
          )

        val jacksonSerializerSym = RootClass.newClass(s.name + "Serializer")

        val jacksonSerializer = OBJECTDEF(jacksonSerializerSym).withParents(
          "com.fasterxml.jackson.databind.ser.std.StdSerializer[" + s.name + "](classOf[" + s.name + "])"
        ) := BLOCK(
          DEF("serialize", UnitClass) withFlags Flags.OVERRIDE withParams (PARAM("value", s.name),
          PARAM("gen", "com.fasterxml.jackson.core.JsonGenerator"),
          PARAM("provider", "com.fasterxml.jackson.databind.SerializerProvider")) := BLOCK(
            (REF("gen") DOT "writeNumber" APPLY (REF("value") DOT "value"))
          )
        )

        Seq(GeneratedObject(s.name, Seq(caseClass, objectDef, jacksonSerializer), Some(jacksonSerializerSym)))
      }
      case s: StringT => {
        val caseClass =
          CASECLASSDEF(s.name) withParents name withParams s.defaultValue.fold(PARAM("value", StringClass).tree) { defaultValue =>
            PARAM("value", StringClass) := LIT(defaultValue)
          }
        val objectDef =
          OBJECTDEF(s.name) := BLOCK(
            Seq(
              OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(s.name) withFlags Flags.IMPLICIT := BLOCK(
                DEF("reads", PLAY_JSON_RESULT(s.name)) withParams PARAM("json", PlayJsValue) := BLOCK(
                  REF("json") DOT "validate" APPLYTYPE StringClass DOT "map" APPLY (REF(s.name) DOT "apply")
                ),
                DEF("writes", PlayJsValue) withParams PARAM("o", s.name) := BLOCK(
                  REF(PlayJsString) APPLY (REF("o") DOT "value")
                )
              )
            ) ++ s.defaultValue.map { defaultValue =>
              VAL("DefaultValue") withType (s.name) := REF(s.name) APPLY ()
            }
          )

        val jacksonSerializerSym = RootClass.newClass(s.name + "Serializer")

        val jacksonSerializer = OBJECTDEF(jacksonSerializerSym).withParents(
          "com.fasterxml.jackson.databind.ser.std.StdSerializer[" + s.name + "](classOf[" + s.name + "])"
        ) := BLOCK(
          DEF("serialize", UnitClass) withFlags Flags.OVERRIDE withParams (PARAM("value", s.name),
          PARAM("gen", "com.fasterxml.jackson.core.JsonGenerator"),
          PARAM("provider", "com.fasterxml.jackson.databind.SerializerProvider")) := BLOCK(
            (REF("gen") DOT "writeString" APPLY (REF("value") DOT "value"))
          )
        )

        Seq(GeneratedObject(s.name, Seq(caseClass, objectDef, jacksonSerializer), Some(jacksonSerializerSym)))
      }
      case t => Visitor.visit(t).objects
    }

    GeneratedFile(
      children
        ++
          Seq(GeneratedObject(name, Seq(base) ++ Seq(obj), Option.empty))
    )
  }
}
