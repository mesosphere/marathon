package mesosphere.raml.ir

import mesosphere.raml.backend._

import treehugger.forest._
import definitions._
import treehuggerDSL._

case class UnionT(name: String, childTypes: Seq[GeneratedClass], comments: Seq[String]) extends GeneratedClass {
  override def toString: String = s"Union($name, $childTypes)"

  // TODO(karsten): This is actually part of the back end.
  override def toTree(): Seq[Tree] = {
    val base = (TRAITDEF(name) withParents("RamlGenerated", "Product", "Serializable")).tree.withDoc(comments)
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
              CASE(REF(s"f:${child.name}")) ==> (REF(PlayJson) DOT "toJson" APPLY REF("f") APPLY(REF(child.name) DOT "playJsonFormat"))
            }
        )
      )
    )
    val children = childTypes.flatMap {
      case s: StringT =>
        Seq[Tree](
          CASECLASSDEF(s.name) withParents name withParams s.defaultValue.fold(PARAM("value", StringClass).tree){ defaultValue =>
            PARAM("value", StringClass) := LIT(defaultValue)
          },
          OBJECTDEF(s.name) := BLOCK(
            Seq(OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(s.name) withFlags Flags.IMPLICIT := BLOCK(
              DEF("reads", PLAY_JSON_RESULT(s.name)) withParams PARAM("json", PlayJsValue) := BLOCK(
                REF("json") DOT "validate" APPLYTYPE StringClass DOT "map" APPLY (REF(s.name) DOT "apply")
              ),
              DEF("writes", PlayJsValue) withParams PARAM("o", s.name) := BLOCK(
                REF(PlayJsString) APPLY (REF("o") DOT "value")
              )
            )) ++ s.defaultValue.map{ defaultValue =>
              VAL("DefaultValue") withType(s.name) := REF(s.name) APPLY()
            }
          )
        )
      case t => t.toTree()
    }
    Seq(base) ++ children ++ Seq(obj)
  }
}

