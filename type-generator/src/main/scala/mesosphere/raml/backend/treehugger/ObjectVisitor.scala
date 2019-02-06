package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir.ObjectT

import treehugger.forest._
import definitions._
import treehuggerDSL._

object ObjectVisitor {
  import mesosphere.raml.backend._

  def visit(o: ObjectT): Seq[Tree] = {
    val ObjectT(name, fields, parentType, comments, childTypes, discriminator, discriminatorValue, serializeOnly) = o

    val actualFields = fields.filter(_.rawName != discriminator.getOrElse(""))
    val params = FieldVisitor.visit(actualFields)
    val klass = if (childTypes.nonEmpty) {
      if (params.nonEmpty) {
        parentType.fold(TRAITDEF(name) withParents("RamlGenerated", "Product", "Serializable") := BLOCK(params))(parent =>
          TRAITDEF(name) withParents(parent, "Product", "Serializable") := BLOCK(params)
        )
      } else {
        parentType.fold((TRAITDEF(name) withParents("RamlGenerated", "Product", "Serializable")).tree)(parent =>
          (TRAITDEF(name) withParents(parent, "Product", "Serializable")).tree
        )
      }
    } else {
      parentType.fold(CASECLASSDEF(name) withParents("RamlGenerated") withParams params)(parent =>
        CASECLASSDEF(name) withParams params withParents parent
      ).tree
    }

    val playFormat = if (discriminator.isDefined) {
      Seq(
        IMPORT("play.api.libs.json._"),

        OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
          DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := BLOCK(
            if (actualFields.size > 1) {
              Seq(IMPORT("play.api.libs.functional.syntax._"),
                actualFields.map(_.playReader).reduce(_ DOT "and" APPLY _) DOT "apply" APPLY (REF(name) DOT "apply _") DOT "reads" APPLY REF("json"))
            } else if (actualFields.size == 1) {
              Seq(actualFields.head.playReader DOT "map" APPLY(REF(name) DOT "apply _") DOT "reads" APPLY REF("json"))
            } else {
              Seq(REF(name))
            }
          ),
          DEF("writes", PlayJsObject) withParams PARAM("o", name) := {
            REF(PlayJson) DOT "obj" APPLY
              fields.map { field =>
                if (field.rawName == discriminator.get) {
                  TUPLE(LIT(field.rawName), REF(PlayJson) DOT "toJsFieldJsValueWrapper" APPLY(PlayJson DOT "toJson" APPLY LIT(discriminatorValue.getOrElse(name))))
                } else {
                  TUPLE(LIT(field.rawName), REF(PlayJson) DOT "toJsFieldJsValueWrapper" APPLY(PlayJson DOT "toJson" APPLY (REF("o") DOT field.rawName)))
                }
              }
          }
        )
      )
    } else if (actualFields.nonEmpty && actualFields.exists(_.default.nonEmpty) && !actualFields.exists(f => f.repeated || f.omitEmpty || f.constraints.nonEmpty)) {
      Seq(
        IMPORT("play.api.libs.json._"),
        IMPORT("play.api.libs.functional.syntax._"),
        VAL("playJsonReader") withType PLAY_JSON_READS(name) := TUPLE(
          actualFields.map(_.playReader).reduce(_ DOT "and" APPLY _)
        ) APPLY (REF(name) DOT "apply _"),
        VAL("playJsonWriter") withType PLAY_JSON_WRITES(name) := REF(PlayJson) DOT "writes" APPLYTYPE (name),
        OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
          DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := BLOCK(
            REF("playJsonReader") DOT "reads" APPLY(REF("json"))
          ),
          DEF("writes", PlayJsValue) withParams PARAM("o", name) := BLOCK(
            REF("playJsonWriter") DOT "writes" APPLY REF("o")
          )
        )
      )
    } else if (actualFields.size > 22 || actualFields.exists(f => f.repeated || f.omitEmpty || f.constraints.nonEmpty) ||
      actualFields.map(_.toString).exists(t => t.toString.startsWith(name) || t.toString.contains(s"[$name]"))) {
      actualFields.map(_.constraints).requiredImports ++ Seq(
        OBJECTDEF("playJsonFormat") withParents (if (serializeOnly) PLAY_JSON_WRITES(name) else PLAY_JSON_FORMAT(name)) withFlags Flags.IMPLICIT := BLOCK(
          if (serializeOnly) {
            Seq()
          } else  Seq(DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := {
            BLOCK(
              actualFields.map { field =>
                VAL(field.name) := FieldVisitor.playValidator(field)
              } ++ Seq(
                VAL("_errors") := SEQ(actualFields.map(f => TUPLE(LIT(f.rawName), REF(f.name)))) DOT "collect" APPLY BLOCK(
                  CASE(REF(s"(field, e:$PlayJsError)")) ==> (REF("e") DOT "repath" APPLY (REF(PlayPath) DOT "\\" APPLY REF("field"))) DOT s"asInstanceOf[$PlayJsError]"),
                IF(REF("_errors") DOT "nonEmpty") THEN (
                  REF("_errors") DOT "reduceOption" APPLYTYPE PlayJsError APPLY (REF("_") DOT "++" APPLY REF("_")) DOT "getOrElse" APPLY (REF("_errors") DOT "head")
                  ) ELSE (
                  REF(PlayJsSuccess) APPLY (REF(name) APPLY
                    actualFields.map { field =>
                      REF(field.name) := (REF(field.name) DOT "get")
                    }))
              )
            )
          }) ++ Seq(
            DEF("writes", PlayJsValue) withParams PARAM("o", name) := BLOCK(
              actualFields.withFilter(_.name != AdditionalProperties).map { field =>
                val serialized = REF(PlayJson) DOT "toJson" APPLY (REF("o") DOT field.name)
                if (field.omitEmpty && field.repeated && !field.forceOptional) {
                  VAL(field.name) := IF(REF("o") DOT field.name DOT "nonEmpty") THEN (
                    serialized
                    ) ELSE (
                    PlayJsNull
                    )
                } else if(field.omitEmpty && !field.repeated && !builtInTypes.contains(field.`type`.toString())) {
                  // earlier "require" check ensures that we won't see a field w/ omitEmpty that is not optional.
                  // see buildTypes
                  VAL(field.name) := serialized MATCH(
                    // avoid serializing JS objects w/o any fields
                    CASE(ID("obj") withType (PlayJsObject),
                      IF(REF("obj.fields") DOT "isEmpty")) ==> PlayJsNull,
                    CASE(ID("rs")) ==> REF("rs")
                  )
                } else {
                  VAL(field.name) := serialized
                }
              } ++
                Seq(
                  REF(PlayJsObject) APPLY (SEQ(
                    actualFields.withFilter(_.name != AdditionalProperties).map { field =>
                      TUPLE(LIT(field.rawName), REF(field.name))
                    }) DOT "filter" APPLY (REF("_._2") INFIX("!=") APPLY PlayJsNull) DOT("++") APPLY(
                    actualFields.find(_.name == AdditionalProperties).fold(REF("Seq") DOT "empty") { extraPropertiesField =>
                      REF("o.additionalProperties") DOT "fields"
                    })
                    )
                )
            )
          )
        )
      )
    } else {
      Seq(VAL("playJsonFormat") withFlags Flags.IMPLICIT := REF("play.api.libs.json.Json") DOT "format" APPLYTYPE (name))
    }

    val defaultFields = fields.withFilter(_.paramTypeValue.nonEmpty).flatMap { f =>
      val (dType, dValue) = f.paramTypeValue.get
      val fieldName = (
        if (f.name.contains("-")) underscoreToCamel(f.name.replace('-', '_')) else f.name
        ).replace("`", "").capitalize
      Seq(VAL(s"Default${fieldName}") withType(dType) := dValue)
    }

    val defaultInstance: Seq[Tree] =
      if (fields.forall(f => f.defaultValue.nonEmpty || f.forceOptional || (f.repeated && !f.required))) {
        Seq(VAL("Default") withType (name) := REF(name) APPLY())
      } else Nil

    val obj = if (childTypes.isEmpty || serializeOnly) {
      (OBJECTDEF(name)) := BLOCK(
        playFormat ++ defaultFields ++ defaultInstance ++ fields.flatMap { f =>
          f.constraints.flatMap { constraint => FieldVisitor.limitField(constraint, f) }
        }
      )
    } else if (discriminator.isDefined) {
      val childDiscriminators: Map[String, ObjectT] = childTypes.map(ct => ct.discriminatorValue.getOrElse(ct.name) -> ct)(collection.breakOut)
      OBJECTDEF(name) := BLOCK(
        Seq(OBJECTDEF("PlayJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
          DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := {
            TUPLE(REF("json") DOT "\\" APPLY LIT(discriminator.get)) DOT "validate" APPLYTYPE (StringClass) MATCH (
              childDiscriminators.map { case (k, v) =>
                CASE(PlayJsSuccess APPLY(LIT(k), REF("_"))) ==> (REF("json") DOT "validate" APPLYTYPE (v.name))
              } ++
                Seq(
                  CASE(WILDCARD) ==> (REF(PlayJsError) APPLY (REF(PlayValidationError) APPLY(LIT("error.expected.jsstring"), LIT(s"expected one of (${childDiscriminators.keys.mkString(", ")})"))))
                )
              )
          },
          DEF("writes", PlayJsValue) withParams PARAM("o", name) := BLOCK(
            REF("o") MATCH
              childDiscriminators.map { case (k, v) =>
                CASE(REF(s"f:${v.name}")) ==> (REF(PlayJson) DOT "toJson" APPLY REF("f") APPLY(REF(v.name) DOT "playJsonFormat"))
              }
          )
        )) ++ defaultFields ++ defaultInstance
      )
    } else {
      System.err.println(s"[WARNING] $name uses subtyping but has no discriminator. If it is not a union type when it is" +
        " used, it will not be able to be deserialized at this time")
      OBJECTDEF(name) := BLOCK(defaultFields ++ defaultInstance)
    }

    val commentBlock = comments ++ actualFields.map(_.comment)(collection.breakOut)
    Seq(klass.withDoc(commentBlock)) ++ childTypes.flatMap(Visitor.visit(_)) ++ Seq(obj)
  }
}
