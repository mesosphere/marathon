package mesosphere.raml

import treehugger.forest._
import definitions._
import org.raml.v2.api.RamlModelResult
import org.raml.v2.api.model.v10.api.Library
import org.raml.v2.api.model.v10.datamodel._
import treehuggerDSL._

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.immutable.Seq

// scalastyle:off
object RamlTypeGenerator {
  val AdditionalProperties = "additionalProperties"
  val baseTypeTable: Map[String, Symbol] =
    Map(
      "string" -> StringClass,
      "int8" -> ByteClass,
      "int16" -> ShortClass,
      "int32" -> IntClass,
      "integer" -> IntClass,
      "int64" -> LongClass,
      "long" -> LongClass,
      "float" -> FloatClass,
      "double" -> DoubleClass,
      "boolean" -> BooleanClass,
      "date-only" -> RootClass.newClass("java.time.LocalDate"),
      "time-only" -> RootClass.newClass("java.time.LocalTime"),
      "datetime-only" -> RootClass.newClass("java.time.LocalDateTime"),
      "datetime" -> RootClass.newClass("java.time.OffsetDateTime"),
      "RamlGenerated" -> RootClass.newClass("RamlGenerated")
    )

  val builtInTypes = Set(
    "Byte",
    "Short",
    "Int",
    "Long",
    "Float",
    "Double",
    "Boolean",
    "String",
    "java.time.LocalDate",
    "java.time.LocalTime",
    "java.time.LocalDateTime",
    "java.time.OffsetDateTime"
  )

  val TryClass = RootClass.newClass("scala.util.Try")

  val SeqClass = RootClass.newClass("scala.collection.immutable.Seq")
  val SetClass = RootClass.newClass("Set")

  def TYPE_SEQ(typ: Type): Type = SeqClass TYPE_OF typ

  val PlayJsonFormat = RootClass.newClass("play.api.libs.json.Format")

  def PLAY_JSON_FORMAT(typ: Type): Type = PlayJsonFormat TYPE_OF typ

  val PlayJsonResult = RootClass.newClass("play.api.libs.json.JsResult")

  def PLAY_JSON_RESULT(typ: Type): Type = PlayJsonResult TYPE_OF typ

  val PlayJson = RootClass.newClass("play.api.libs.json.Json")
  val PlayJsValue = RootClass.newClass("play.api.libs.json.JsValue")
  val PlayJsString = RootClass.newClass("play.api.libs.json.JsString")
  val PlayJsObject = RootClass.newClass("play.api.libs.json.JsObject")
  val PlayJsArray = RootClass.newClass("play.api.libs.json.JsArray")
  val PlayValidationError = RootClass.newClass("play.api.data.validation.ValidationError")
  val PlayJsError = RootClass.newClass("play.api.libs.json.JsError")
  val PlayJsSuccess = RootClass.newClass("play.api.libs.json.JsSuccess")
  val PlayReads = RootClass.newClass("play.api.libs.json.Reads")
  def PLAY_JSON_READS(typ: Type): Type = PlayReads TYPE_OF typ
  val PlayWrites = RootClass.newClass("play.api.libs.json.Writes")
  def PLAY_JSON_WRITES(typ: Type): Type = PlayWrites TYPE_OF typ
  val PlayPath = RootClass.newClass("play.api.libs.json.JsPath")

  val PlayJsNull = REF("play.api.libs.json.JsNull")

  /**
    * we don't support unit test generation for RAML-generated scala code. to subvert the
    * code coverage calculator this string may be inserted as a comment at the top of a
    * scala file, just before the package declaration(s).
    *
    * order of operations is important: COVERAGE-OFF should appear at the top of the file,
    * before package declarations. so `withComment` should be applied directly to package
    * declarations (`inPackage`)
    */
  val NoCodeCoverageReporting = "$COVERAGE-OFF$"

  def camelify(name: String): String = name.toLowerCase.capitalize

  def underscoreToCamel(name: String) = "(/|_|\\,)([a-z\\d])".r.replaceAllIn(name, { m =>
    m.group(2).toUpperCase()
  })

  def enumName(s: StringTypeDeclaration, default: Option[String] = None): String = {
    s.annotations().find(_.name() == "(pragma.scalaType)").fold(default.getOrElse(s.name()).capitalize) { annotation =>
      annotation.structuredValue().value().toString
    }
  }

  def objectName(o: ObjectTypeDeclaration): (String, Option[String]) = {
    if (o.`type` == "object") {
      o.name() -> None
    } else if (o.name == "items") {
      o.`type` -> None
    } else if (o.name == "/.*/") {
      o.`type` -> None
    } else {
      o.name() -> Some(o.`type`())
    }
  }
  
  def scalaFieldName(name: String): String = {
    if (name.contains("-")) s"`$name`"
    else name
  }

  def isUpdateType(o: ObjectTypeDeclaration): Boolean =
    (o.`type`() == "object") && o.annotations.exists(_.name() == "(pragma.asUpdateType)")

  def isOmitEmpty(field: TypeDeclaration): Boolean =
    field.annotations.exists(_.name() == "(pragma.omitEmpty)")

  def pragmaForceOptional(o: TypeDeclaration): Boolean =
    o.annotations().exists(_.name() == "(pragma.forceOptional)")

  def generateUpdateTypeName(o: ObjectTypeDeclaration): Option[String] =
    if (o.`type`() == "object" && !isUpdateType(o)) {
      // use the attribute value as the type name if specified ala enumName; otherwise just append "Update"
      o.annotations().find(_.name() == "(pragma.generateUpdateType)").map { annotation =>
        Option(annotation.structuredValue().value()).fold(o.name()+"Update")(_.toString)
      }
    } else {
      None
    }

  def buildTypeTable(types: Set[TypeDeclaration]): Map[String, Symbol] = {
    @tailrec def build(types: Set[TypeDeclaration], result: Map[String, Symbol]): Map[String, Symbol] = {
      types match {
        case s if s.nonEmpty =>
          s.head match {
            case a: ArrayTypeDeclaration if a.items().`type`() != "string" =>
              sys.error(s"${a.name()} : ${a.items().name()} ${a.items.`type`} ArrayTypes should be declared as ObjectName[]")
            case o: ObjectTypeDeclaration =>
              val (name, _) = objectName(o)
              val updateTypeName = generateUpdateTypeName(o)
              val normalTypeName = Some(name)
              val next = Seq(updateTypeName, normalTypeName).flatten.map(n => n -> RootClass.newClass(n))
              build(s.tail, result ++ next)
            case u: UnionTypeDeclaration =>
              build(s.tail, result + (u.name() -> RootClass.newClass(u.name)))
            case e: StringTypeDeclaration if e.enumValues().nonEmpty =>
              build(s.tail, result + (e.name -> RootClass.newClass(e.name)))
            case str: StringTypeDeclaration =>
              build(s.tail, result + (str.name -> StringClass))
            case n: NumberTypeDeclaration =>
              build(s.tail, result + (n.name -> result(n.format())))
            case _ =>
              build(s.tail, result)
          }
        case _ =>
          result
      }
    }
    build(types, baseTypeTable)
  }

  sealed trait GeneratedClass {
    val name: String

    def toTree(): Seq[Tree]
  }

  case class EnumT(name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
    val sortedValues = values.toVector.sorted
    override def toString: String = s"Enum($name, $values)"

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
        (REF(PlayJsError) APPLY (REF(PlayValidationError) APPLY(LIT("error.expected.jsstring"), LIT(s"$name (${sortedValues.mkString(", ")})"))))
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
          DEF("fromString", TYPE_OPTION(name)) withParams(PARAM("v", StringClass)) := REF("StringToValue") DOT "get" APPLY(REF("v"))
        ) ++ default.map { defaultValue =>
          VAL("DefaultValue") withType(name) := REF(underscoreToCamel(camelify(defaultValue)))
        }
      )
      Seq(baseTrait.withDoc(comments), obj)
    }
  }

  sealed trait Constraint {
    def validate(): Tree
  }

  object Constraint {
    def MaxLength(len: Integer) = Constraint { REF("maxLength") APPLYTYPE StringClass APPLY(LIT(len)) }
    def MinLength(len: Integer) = Constraint { REF("minLength") APPLYTYPE StringClass APPLY(LIT(len)) }
    def Pattern(p: String) = Constraint { REF("pattern") APPLY(LIT(p) DOT "r") }

    def MaxItems(len: Integer, t: Type) = Constraint { REF("maxLength") APPLYTYPE t APPLY(LIT(len)) }
    def MinItems(len: Integer, t: Type) = Constraint { REF("minLength") APPLYTYPE t APPLY(LIT(len)) }

    def Max(v: Number, t: Type) = Constraint { REF("max") APPLYTYPE t APPLY(LIT(v)) }
    def Min(v: Number, t: Type) = Constraint { REF("min") APPLYTYPE t APPLY(LIT(v)) }

    def apply(f: => Tree): Constraint = new Constraint {
      override def validate(): Tree = f
    }

    implicit class Constraints(c: Seq[Constraint]) {
      def validate(exp: Tree): Tree = {
        if (c.isEmpty) {
          exp
        } else {
          @tailrec
          def buildChain(constraints: List[ Constraint ], chain: Tree): Tree = constraints match {
            case Nil => chain
            case c :: rs => buildChain(rs, chain INFIX("keepAnd", c.validate()))
          }
          exp APPLY buildChain(c.tail.to[ List ], c.head.validate())
        }
      }
    }
  }

  case class FieldT(rawName: String, `type`: Type, comments: Seq[String], constraints: Seq[Constraint], required: Boolean,
                    default: Option[String], repeated: Boolean = false, forceOptional: Boolean = false, omitEmpty: Boolean = false) {

    val name = scalaFieldName(rawName)
    override def toString: String = s"$name: ${`type`}"

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

    lazy val param: treehugger.forest.ValDef =
      paramTypeValue.fold { PARAM(name, `type`).tree } { case (pType, pValue) => PARAM(name, pType) := pValue }

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
        TUPLE(REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`
      } else if (repeated && !forceOptional) {
        TUPLE(REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type` DOT "orElse" APPLY(REF(PlayReads) DOT "pure" APPLY(`type` APPLY()))
      } else {
        if (defaultValue.isDefined && !forceOptional) {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`) DOT "orElse" APPLY (REF(PlayReads) DOT "pure" APPLY defaultValue.get)
        } else {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "readNullable" APPLYTYPE `type`)
        }
      }
    }

    val playValidator = {
      def reads = constraints.validate(PlayPath DOT "read" APPLYTYPE `type`)
      def validate =
        REF("json") DOT "\\" APPLY(LIT(name)) DOT "validate" APPLYTYPE `type` APPLY(reads)
      def validateOpt =
        REF("json") DOT "\\" APPLY(LIT(name)) DOT "validateOpt" APPLYTYPE `type` APPLY(reads)
      def validateOptWithDefault(defaultValue: Tree) =
        REF("json") DOT "\\" APPLY(LIT(name)) DOT "validateOpt" APPLYTYPE `type` APPLY(reads) DOT "map" APPLY (REF("_") DOT "getOrElse" APPLY defaultValue)

      if (required && !forceOptional) {
        validate
      } else if (repeated && !forceOptional) {
        validateOptWithDefault(`type` APPLY())
      } else {
        if (defaultValue.isDefined && !forceOptional) {
          validateOptWithDefault(defaultValue.get)
        } else {
          validateOpt
        }
      }
    }
  }

  case class ObjectT(name: String, fields: Seq[FieldT], parentType: Option[String], comments: Seq[String], childTypes: Seq[ObjectT] = Nil, discriminator: Option[String] = None, discriminatorValue: Option[String] = None) extends GeneratedClass {
    override def toString: String = parentType.fold(s"$name(${fields.mkString(", ")})")(parent => s"$name(${fields.mkString(" , ")}) extends $parent")

    override def toTree(): Seq[Tree] = {
      val actualFields = fields.filter(_.rawName != discriminator.getOrElse(""))
      val params = actualFields.map(_.param)
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
        actualFields.find(_.constraints.nonEmpty).map(_ => Seq(IMPORT(PlayReads DOT "_"))).getOrElse(Nil) ++
        actualFields.find(_.constraints.size > 1).map(_ => Seq(IMPORT("play.api.libs.functional.syntax._"))).getOrElse(Nil) ++ Seq(
          OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
            DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := BLOCK(
              actualFields.map { field =>
                VAL(field.name) := field.playValidator
              } ++ Seq(
                VAL("_errors") := SEQ(actualFields.map(f => TUPLE(LIT(f.name), REF(f.name)))) DOT "collect" APPLY BLOCK(
                  CASE(REF(s"(field, e:$PlayJsError)")) ==> (REF("e") DOT "repath" APPLY(REF(PlayPath) DOT "\\" APPLY REF("field"))) DOT s"asInstanceOf[$PlayJsError]"),
                IF(REF("_errors") DOT "nonEmpty") THEN (
                  REF("_errors") DOT "reduceOption" APPLYTYPE PlayJsError APPLY (REF("_") DOT "++" APPLY REF("_")) DOT "getOrElse" APPLY (REF("_errors") DOT "head")
                  ) ELSE (
                  REF(PlayJsSuccess) APPLY (REF(name) APPLY
                    actualFields.map { field =>
                      REF(field.name) := (REF(field.name) DOT "get")
                    }))
              )
            ),
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
                      TUPLE(LIT(field.name), REF(field.name))
                    }) DOT "filter" APPLY (REF("_._2") INFIX("!=") APPLY PlayJsNull) DOT("++") APPLY(
                      actualFields.find(_.name == AdditionalProperties).fold(REF("Seq") DOT "empty") { extraPropertiesField =>
                      REF("o.additionalProperties") DOT "fields"
                    })
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

      val obj = if (childTypes.isEmpty) {
        (OBJECTDEF(name)) := BLOCK(
          playFormat ++ defaultFields ++ defaultInstance
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
      Seq(klass.withDoc(commentBlock)) ++ childTypes.flatMap(_.toTree()) ++ Seq(obj)
    }
  }

  case class StringT(name: String, defaultValue: Option[String]) extends GeneratedClass {
    override def toTree(): Seq[treehugger.forest.Tree] = Seq.empty[Tree]
  }

  case class UnionT(name: String, childTypes: Seq[GeneratedClass], comments: Seq[String]) extends GeneratedClass {
    override def toString: String = s"Union($name, $childTypes)"

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

  @tailrec def libraryTypes(libraries: List[Library], result: Set[TypeDeclaration] = Set.empty): Set[TypeDeclaration] = {
    libraries match {
      case head :: tail =>
        libraryTypes(head.uses.toList ::: tail, result ++ head.types().toSet)
      case Nil =>
        result
    }
  }

  @tailrec def allTypes(models: Seq[RamlModelResult], result: Set[TypeDeclaration] = Set.empty): Set[TypeDeclaration] = {
    models match {
      case head +: tail =>
        val types = libraryTypes(Option(head.getLibrary).toList) ++
          libraryTypes(Option(head.getApiV10).map(_.uses().toList).getOrElse(Nil))
        allTypes(tail, result ++ types)
      case Nil =>
        result
    }
  }

  def comment(t: TypeDeclaration): Seq[String] = {
    def escapeDesc(s: Option[String]): Option[String] =
      s.map(_.replace("$", "$$"))

    t match {
      case a: ArrayTypeDeclaration =>
        Seq(escapeDesc(Option(a.description()).map(_.value)),
          Option(a.minItems()).map(i => s"minItems: $i"),
          Option(a.maxItems()).map(i => s"maxItems: $i")).flatten
      case o: ObjectTypeDeclaration =>
        Seq(escapeDesc(Option(o.description()).map(_.value)),
          Option(o.example()).map(e => s"Example: <pre>${e.value}</pre>")).flatten
      case s: StringTypeDeclaration =>
        Seq(escapeDesc(Option(s.description()).map(_.value)),
          Option(s.maxLength()).map(i => s"maxLength: $i"),
          Option(s.minLength()).map(i => s"minLength: $i"),
          Option(s.pattern()).map(i => s"pattern: <pre>$i</pre>")).flatten
      case n: NumberTypeDeclaration =>
        Seq(escapeDesc(Option(n.description()).map(_.value)),
          Option(n.minimum()).map(i => s"minimum: $i"),
          Option(n.maximum()).map(i => s"maximum: $i"),
          Option(n.multipleOf()).map(i => s"multipleOf: $i")).flatten
      case _ =>
        Seq(escapeDesc(Option(t.description()).map(_.value()))).flatten
    }
  }

  def typeIsActuallyAMap(t: TypeDeclaration): Boolean = t match {
    case o: ObjectTypeDeclaration =>
      o.properties.toList match {
        case field :: Nil if field.name().startsWith('/') => true
        case _ => false
      }
    case _ => false
  }

  def buildTypes(typeTable: Map[String, Symbol], allTypes: Set[TypeDeclaration]): Set[GeneratedClass] = {
    @tailrec def buildTypes(types: Set[TypeDeclaration], results: Set[GeneratedClass] = Set.empty[GeneratedClass]): Set[GeneratedClass] = {
      def buildConstraints(field: TypeDeclaration, fieldType: Type): Seq[Constraint] = {
        Option(field).collect {
          case s: StringTypeDeclaration =>
            Seq(
              Option(s.maxLength()).map(Constraint.MaxLength),
              Option(s.minLength()).map(Constraint.MinLength),
              Option(s.pattern()).map(Constraint.Pattern)
            ).flatten
          case a: ArrayTypeDeclaration =>
            Seq(
              Option(a.maxItems()).map(len => Constraint.MaxItems(len, fieldType)),
              Option(a.minItems()).map(len => Constraint.MinItems(len, fieldType))
            ).flatten
          case n: NumberTypeDeclaration =>
            // convert numbers so that constraints are appropriately rendered
            def toNum(v: Double): Number = fieldType match {
              case DoubleClass => v
              case FloatClass => v.toFloat
              case LongClass => v.toLong
              case _ => v.toInt
            }

            Seq(
              Option(n.maximum()).map(v => Constraint.Max(toNum(v), fieldType)),
              Option(n.minimum()).map(v => Constraint.Min(toNum(v), fieldType))
            ).flatten
        }.getOrElse(Nil)
      }
      def createField(fieldOwner: String, field: TypeDeclaration): FieldT = {
        val comments = comment(field)
        val defaultValue = Option(field.defaultValue())
        // if a field has a default, its not required.
        val required = defaultValue.fold(Option(field.required()).fold(false)(_.booleanValue()))(_ => false)
        val forceOptional = pragmaForceOptional(field)
        val omitEmpty = isOmitEmpty(field)

        // see ObjectT.playFormat
        require(!(((required || defaultValue.nonEmpty) && !forceOptional) && omitEmpty),
          s"field $fieldOwner.${field.name()} specifies omitEmpty but is required or provides a default value")

        def arrayType(a: ArrayTypeDeclaration): Type =
          if (scala.util.Try[Boolean](a.uniqueItems()).getOrElse(false)) SetClass else SeqClass

        field match {
          case a: ArrayTypeDeclaration =>
            @tailrec def arrayTypes(a: ArrayTypeDeclaration, types: List[Type]): List[Type] = {
              a.items() match {
                case n: ArrayTypeDeclaration =>
                  arrayTypes(n, arrayType(n) :: types)
                case o: ObjectTypeDeclaration =>
                  objectName(o)._1 :: types
                case n: NumberTypeDeclaration =>
                  typeTable(Option(n.format()).getOrElse("double")) :: types
                case t: TypeDeclaration =>
                  typeTable(t.`type`.replaceAll("\\[\\]", "")) :: types
              }
            }
            val typeList = arrayTypes(a, List(arrayType(a)))
            // reducing with TYPE_OF doesn't work, you'd expect Seq[Seq[X]] but only get Seq[X]
            // https://github.com/eed3si9n/treehugger/issues/38
            val finalType = typeList.reduce((a, b) => s"$b[$a]")
            FieldT(a.name(), finalType, comments, buildConstraints(field, finalType), required, defaultValue, true, forceOptional, omitEmpty = omitEmpty)
          case n: NumberTypeDeclaration =>
            val fieldType = typeTable(Option(n.format()).getOrElse("double"))
            FieldT(n.name(), fieldType, comments, buildConstraints(field, fieldType), required, defaultValue, forceOptional = forceOptional, omitEmpty = omitEmpty)
          case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
            o.properties.head match {
              case n: NumberTypeDeclaration =>
                FieldT(o.name(), TYPE_MAP(StringClass, typeTable(Option(n.format()).getOrElse("double"))), comments, Nil, false, defaultValue, true, forceOptional = forceOptional, omitEmpty = omitEmpty)
              case t =>
                FieldT(o.name(), TYPE_MAP(StringClass, typeTable(t.`type`())), comments, Nil, false, defaultValue, true, forceOptional = forceOptional, omitEmpty = omitEmpty)
            }
          case t: TypeDeclaration =>
            val (name, fieldType) = if (t.`type`() != "object") {
              t.name() -> typeTable(t.`type`())
            } else {
              AdditionalProperties -> PlayJsObject
            }
            FieldT(name, fieldType, comments, buildConstraints(field, fieldType), required, defaultValue, forceOptional = forceOptional, omitEmpty = omitEmpty)
        }
      }

      types match {
        case s if s.nonEmpty =>
          s.head match {
            case a: ArrayTypeDeclaration if a.items().`type`() != "string" =>
              sys.error("Can't build array types")
            case u: UnionTypeDeclaration =>
              if (!results.exists(_.name == u.name())) {
                val subTypeNames = u.`type`().split("\\|").map(_.trim)
                val subTypeDeclarations = subTypeNames.flatMap { t => allTypes.find(_.name == t) }
                val subTypes = subTypeDeclarations.map {
                  case o: ObjectTypeDeclaration =>
                    val (name, parent) = objectName(o)
                    val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(f => createField(name, f))(collection.breakOut)
                    ObjectT(name, fields, parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
                  case s: StringTypeDeclaration =>
                    StringT(s.name, Option(s.defaultValue()))
                  case t =>
                    sys.error(s"Unable to generate union types of non-object/string subtypes: ${u.name()} ${t.name()} ${t.`type`()}")
                }
                val unionType = UnionT(u.name(), subTypes.toVector, comment(u))
                buildTypes(s.tail, results + unionType ++ subTypes)
              } else {
                buildTypes(s.tail, results)
              }
            case o: ObjectTypeDeclaration if !typeIsActuallyAMap(o) =>
              if (!results.exists(_.name == o.name())) {
                val (name, parent) = objectName(o)
                val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(f => createField(name, f))(collection.breakOut)
                if (isUpdateType(o)) {
                  val objectType = ObjectT(name, fields.map(_.copy(forceOptional = true)), parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
                  buildTypes(s.tail, results + objectType)
                } else {
                  val objectType = ObjectT(name, fields, parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
                  val updateType = generateUpdateTypeName(o).withFilter(n => !results.exists(_.name == n)).map { updateName =>
                    objectType.copy(name = updateName, fields = fields.map(_.copy(forceOptional = true)))
                  }
                  buildTypes(s.tail, results ++ Seq(Some(objectType), updateType).flatten)
                }
              } else {
                buildTypes(s.tail, results)
              }
            case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
              buildTypes(s.tail, results)
            case e: StringTypeDeclaration if e.enumValues().nonEmpty =>
              val enumType = EnumT(e.name(),
                e.enumValues().toSet,
                Option(e.defaultValue()),
                comment(e))
              buildTypes(s.tail, results + enumType)
            case _ =>
              buildTypes(s.tail, results)
          }
        case _ =>
          results
      }
    }
    val all = buildTypes(allTypes)
    val childTypes = all.collect { case obj: ObjectT if obj.parentType.isDefined => obj }.groupBy(_.parentType.get)
    val childNames = childTypes.values.flatMap(_.map(_.name)).toSet


    val unionTypeNames = all.collect { case u: UnionT => u }.flatMap { t => t.childTypes.map(_.name) }

    // Reduce the list so that union types and type hierarchies are now all included from just the top-level type
    val filterPhase1 = all.withFilter(t => !unionTypeNames.contains(t.name) && !childNames.contains(t.name) && !t.isInstanceOf[StringT]).map {
      case u: UnionT =>
        val children = u.childTypes.map {
          case o: ObjectT =>
            o.copy(parentType = Some(u.name))
          case t => t
        }
        u.copy(childTypes = children)
      case obj: ObjectT if childTypes.containsKey(obj.name) =>
        val children = childTypes(obj.name)
        obj.copy(childTypes = children.to[Seq])
      case t => t
    }
    filterPhase1.filter {
      case o: ObjectT =>
        !o.childTypes.map(_.name).exists(unionTypeNames.contains)
      case t => true
    }
  }

  def generateBuiltInTypes(pkg: String): Map[String, Tree] = {
    val baseType = TRAITDEF("RamlGenerated").tree.withDoc("Marker trait indicating generated code.")
      .inPackage(pkg).withComment(NoCodeCoverageReporting)
    Map("RamlGenerated" -> baseType)
  }

  def apply(models: Seq[RamlModelResult], pkg: String): Map[String, Tree] = {
    val typeDeclarations = allTypes(models)
    val typeTable = buildTypeTable(typeDeclarations)
    val types = buildTypes(typeTable, typeDeclarations)

    generateBuiltInTypes(pkg) ++ types.map { tpe =>
      val tree = tpe.toTree()
      if (tree.nonEmpty) {
        tpe.name -> BLOCK(tree).inPackage(pkg).withComment(NoCodeCoverageReporting)
      } else {
        tpe.name -> BLOCK().withComment(s"Unsupported: $tpe").inPackage(pkg).withComment(NoCodeCoverageReporting)
      }
    }(collection.breakOut)
  }
}
