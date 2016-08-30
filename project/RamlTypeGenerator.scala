package mesosphere.raml

import treehugger.forest._
import definitions._
import treehuggerDSL._
import org.raml.v2.api.model.v10.datamodel._

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.immutable.Seq

// scalastyle:off
object RamlTypeGenerator {
  val baseTypeTable: Map[String, Symbol] =
    Map(
      "string" -> StringClass,
      "string[]" -> StringClass,
      "int8" -> ByteClass,
      "int16" -> ShortClass,
      "int32" -> IntClass,
      "int64" -> LongClass,
      "long" -> LongClass,
      "float" -> FloatClass,
      "double" -> DoubleClass,
      "boolean" -> BooleanClass,
      "date-only" -> RootClass.newClass("java.time.LocalDate"),
      "time-only" -> RootClass.newClass("java.time.LocalTime"),
      "datetime-only" -> RootClass.newClass("java.time.LocalDateTime"),
      "datetime" -> RootClass.newClass("java.time.OffsetDateTime"),
      "any" -> RootClass.newClass("scala.Any"),
      "file" -> RootClass.newClass("Base64File")
    )

  val JsonFormat = RootClass.newClass("spray.json.JsonFormat")
  def JSON_FORMAT(typ: Type): Type = JsonFormat TYPE_OF typ
  val RootJsonFormat = RootClass.newClass("spray.json.RootJsonFormat")
  def ROOT_JSON_FORMAT(typ: Type): Type = RootJsonFormat TYPE_OF typ
  val JsValue = RootClass.newClass("spray.json.JsValue")
  val JsString = RootClass.newClass("spray.json.JsString")
  val DefaultJsonProtocol = RootClass.newClass("RamlJsonProtocol")
  val SeqClass = RootClass.newClass("scala.collection.immutable.Seq")

  val PlayJsonFormat = RootClass.newClass("play.api.libs.json.Format")
  def PLAY_JSON_FORMAT(typ: Type): Type = PlayJsonFormat TYPE_OF typ
  val PlayJsonResult = RootClass.newClass("play.api.libs.json.JsResult")
  def PLAY_JSON_RESULT(typ: Type): Type = PlayJsonResult TYPE_OF typ
  val PlayJsValue = RootClass.newClass("play.api.libs.json.JsValue")
  val PlayJsString = RootClass.newClass("play.api.libs.json.JsString")
  val PlayJsArray = RootClass.newClass("play.api.libs.json.JsArray")
  val PlayJsResultEx = RootClass.newClass("play.api.libs.json.JsResultException")
  val PlayJsPath = RootClass.newClass("play.api.libs.json.JsPath")
  val PlayValidationError = RootClass.newClass("play.api.data.validation.ValidationError")
  def TYPE_SEQ(typ: Type): Type = SeqClass TYPE_OF typ
  def camelify(name : String): String = name.toLowerCase.capitalize
  def underscoreToCamel(name: String) = "_([a-z\\d])".r.replaceAllIn(name, {m =>
    m.group(1).toUpperCase()
  })

  def objectName(o: ObjectTypeDeclaration, default: Option[String] = None): String = {
    o.annotations().find(_.name() == "(scalaType)").fold(default.getOrElse(o.name()).capitalize) { annotation =>
      annotation.structuredValue().value().toString
    }
  }

  def enumName(s: StringTypeDeclaration, default: Option[String] = None): String = {
    s.annotations().find(_.name() == "(scalaType)").fold(default.getOrElse(s.name()).capitalize) { annotation =>
      annotation.structuredValue().value().toString
    }
  }

  def buildTypeTable(types: Seq[TypeDeclaration]): Map[String, Symbol] = {
    @tailrec def build(remaining: List[TypeDeclaration], result: Map[String, Symbol]): Map[String, Symbol] = {
      remaining match {
        case head :: tail =>
          head match {
            case a:ArrayTypeDeclaration =>
              a.items() match {
                case o:ObjectTypeDeclaration =>
                  build(o.properties().toList ::: tail, result + (objectName(o, Some(a.name())) -> RootClass.newClass(objectName(o, Some(a.name())))))
                case u:UnionTypeDeclaration =>
                  build(u.of().toList ::: tail, result)
                case _:BooleanTypeDeclaration |
                     _:DateTimeOnlyTypeDeclaration | _:DateTimeTypeDeclaration |
                     _:FileTypeDeclaration | _:IntegerTypeDeclaration |
                     _:NumberTypeDeclaration | _:TimeOnlyTypeDeclaration =>
                  build(tail, result)
                case s:StringTypeDeclaration =>
                  if (!s.enumValues().isEmpty) {
                    build(tail, result + (enumName(s, Some(a.name())) -> RootClass.newClass(enumName(s, Some(a.name())))))
                  } else {
                    build(tail, result)
                  }
                case _ if result.keySet.contains(head.`type`().replaceAll("\\[\\]", "")) =>
                  build(tail, result + (s"${head.name()}[]" -> result(head.`type`.replaceAll("\\[\\]", ""))))
                case unknown =>
                  println(s"Error, unknown type declaration: ${unknown.name()} ${unknown.`type`()} $unknown ${unknown.getClass}")
                  build(tail, result)
              }
            case o:ObjectTypeDeclaration =>
              build(o.properties().toList ::: tail, result + (objectName(o) -> RootClass.newClass(objectName(o))))
            case u:UnionTypeDeclaration =>
              build(u.of().toList ::: tail, result)
            case _:BooleanTypeDeclaration |
                 _:DateTimeOnlyTypeDeclaration | _:DateTimeTypeDeclaration |
                 _:FileTypeDeclaration | _:IntegerTypeDeclaration |
                 _:NumberTypeDeclaration | _:TimeOnlyTypeDeclaration =>
              build(tail, result)
            case s:StringTypeDeclaration =>
              if (!s.enumValues().isEmpty) {
                build(tail, result + (enumName(s) -> RootClass.newClass(enumName(s))))
              } else {
                build(tail, result)
              }
            case unknown =>
              println(s"Error, unknown type declaration: ${unknown.name()} ${unknown.`type`()} $unknown ${unknown.getClass}")
              build(tail, result)
          }
        case Nil =>
          result
      }
    }
    build(types.toList, baseTypeTable)
  }

  def createEnumType(typeTable: Map[String, Symbol], baseName: String, stringType: StringTypeDeclaration): List[Tree] = {
    require(!stringType.enumValues().isEmpty, s"$baseName is not an enum")

    val baseTraitName = enumName(stringType, Some(baseName))

    val baseTrait = TRAITDEF(baseTraitName) withFlags Flags.SEALED := BLOCK(
      VAL("value", StringClass),
      DEF("toString", StringClass) withFlags Flags.OVERRIDE := REF("value")
    )

    val enumObjects = stringType.enumValues().map { enumValue =>
      CASEOBJECTDEF(underscoreToCamel(camelify(enumValue))) withParents typeTable(baseTraitName) := BLOCK(
        VAL("value") := LIT(enumValue.toLowerCase)
      )
    }.toVector

    val patternMatches = stringType.enumValues().map { enumValue =>
      CASE (LIT (enumValue.toLowerCase)) ==> REF(underscoreToCamel(camelify(enumValue)))
    }
    val wildcard = CASE(WILDCARD) ==>
      (REF("spray.json.deserializationError") APPLY LIT(s"Expected ${baseName.capitalize} (${stringType.enumValues().map(_.toLowerCase).mkString(", ")})"))
    patternMatches.append(wildcard)

    val enumJsonFormat = (OBJECTDEF(s"${baseTraitName}JsonFormat") withParents JSON_FORMAT(baseTraitName) withFlags Flags.IMPLICIT) := BLOCK(
      DEF("read", typeTable(baseTraitName)) withParams PARAM("value", JsValue) := {
        REF("value") MATCH(
          CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH patternMatches),
          wildcard
          )
      },
      DEF("write", JsValue) withParams PARAM(baseName, baseTraitName) := {
        REF(JsString) APPLY (REF(baseName) DOT "value")
      }
    )

    val playWildcard = CASE(WILDCARD) ==>
      (REF(PlayJsResultEx) APPLY (SEQ(TUPLE(REF(PlayJsPath) APPLY(), REF(PlayValidationError) APPLY (LIT("error.expected.jsstring"), LIT(s"${baseName.capitalize} (${stringType.enumValues().map(_.toLowerCase).mkString(", ")})"))))))

    val playJsonFormat = (OBJECTDEF(s"${baseTraitName}PlayJsonFormat") withParents PLAY_JSON_FORMAT(typeTable(baseTraitName)) withFlags Flags.IMPLICIT) := BLOCK(
      DEF("read", typeTable(baseTraitName)) withParams PARAM("json", PlayJsValue) := {
        REF("json") MATCH(
          CASE(REF(PlayJsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH patternMatches),
          playWildcard
          )
      },
      DEF("write", PlayJsValue) withParams PARAM(baseName, baseTraitName) := {
        REF(PlayJsString) APPLY (REF(baseName) DOT "value")
      }
    )

    val obj = OBJECTDEF(baseTraitName) := BLOCK(
      enumObjects ++ Seq(enumJsonFormat, playJsonFormat)
    )
    List(baseTrait.withDoc(Option(stringType.description()).map(_.value)), obj)
  }

  def createObjectType(typeTable: Map[String, Symbol], name: String, objectType: ObjectTypeDeclaration): List[Tree] = {
    def paramDef(name: String, param: TypeDeclaration): Type = {
      param match {
        case s: StringTypeDeclaration if !s.enumValues.isEmpty =>
          typeTable(enumName(s, Some(name)))
        case a: ArrayTypeDeclaration =>
          TYPE_SEQ(paramDef(a.name, a.items))
        case o: ObjectTypeDeclaration =>
          typeTable(objectName(o, Some(name)))
        case n: NumberTypeDeclaration =>
          typeTable(Option(n.format()).getOrElse("double"))
        case s: StringTypeDeclaration if s.enumValues().isEmpty =>
          StringClass
        case b: TypeDeclaration =>
          typeTable(b.`type`())
      }
    }

    val params = objectType.properties.map {
      case a:ArrayTypeDeclaration =>
        if (Option(a.minItems()).fold(0)(_.intValue()) < 1) {
          PARAM(a.name, paramDef(a.name(), a)) := NIL
        } else {
          PARAM(a.name, paramDef(a.name(), a)).tree
        }
      case o: ObjectTypeDeclaration =>
        if (o.required()) {
          PARAM(o.name(), typeTable(objectName(o))).tree
        } else {
          PARAM(o.name(), TYPE_OPTION(objectName(o))) := NONE
        }
      case s: StringTypeDeclaration if !s.enumValues().isEmpty =>
        val typeName = enumName(s)
        if (s.required) {
          PARAM(s.name(), typeTable(typeName)).tree
        } else {
          PARAM(s.name(), TYPE_OPTION(typeName)) := NONE
        }
      case s: StringTypeDeclaration if s.enumValues().isEmpty =>
        Option(s.defaultValue()).fold {
          if (s.required()) {
            PARAM(s.name(), StringClass).tree
          } else {
            PARAM(s.name(), TYPE_OPTION(StringClass)) := NONE
          }
        } { defaultValue =>
          if (s.required()) {
            PARAM(s.name(), StringClass) := LIT(defaultValue)
          } else {
            PARAM(s.name(), TYPE_OPTION(StringClass)) := REF(SomeClass) APPLY LIT(defaultValue)
          }
        }
      case n: NumberTypeDeclaration =>
        val typeName = typeTable(Option(n.format()).getOrElse("double"))
        Option(n.defaultValue()).fold {
          if (n.required()) {
            PARAM(n.name(), typeName).tree
          } else {
            PARAM(n.name, TYPE_OPTION(typeName)) := NONE
          }
        } { defaultValue =>
          if (n.required()) {
            PARAM(n.name(), typeName) := LIT(defaultValue)
          } else {
            val default = Option(n.format()).map {
              case "int8" => defaultValue.toByte
              case "int16" => defaultValue.toShort
              case "int32" => defaultValue.toInt
              case "int64" | "long" => defaultValue.toLong
              case "float" => defaultValue.toFloat
            }.getOrElse(defaultValue.toDouble)
            PARAM(n.name, TYPE_OPTION(typeName)) := REF(SomeClass) APPLY LIT(default)
          }
        }

      case arg: TypeDeclaration =>
        if (arg.required()) {
          PARAM(arg.name(), paramDef(arg.name(), arg)).tree
        } else {
          PARAM(arg.name(), TYPE_OPTION(typeTable(arg.`type`()))) := NONE
        }
    }

    val klass = CASECLASSDEF(name.capitalize) withParams params
    val paramDocs = objectType.properties.flatMap { p =>
      Option(p.description()).map(c => DocTag.Param(p.name(), c.value))
    }

    val obj = OBJECTDEF(name.capitalize) withParents(DefaultJsonProtocol) := BLOCK(
      VAL(s"${name}JsonFormat") withFlags Flags.IMPLICIT := REF(s"jsonFormat") APPLY((REF(name.capitalize) DOT "apply _").tree +: objectType.properties.map(p => LIT(p.name))(collection.breakOut)),
      VAL(s"${name}PlayJsonFormat") withFlags Flags.IMPLICIT := REF(s"play.api.libs.json.Json.format[${name.capitalize}]")
    )
    val classDocs = Option(objectType.description()).map(_.value)
    if (classDocs.isDefined || paramDocs.nonEmpty) {
      List(klass.tree.withDoc(Option(objectType.description()).map(_.value), paramDocs : _*), obj)
    } else {
      List(klass, obj)
    }
  }

  def generateTypes(pkg: String, types: Seq[TypeDeclaration]): Map[String, Tree] = {
    val typeTable = buildTypeTable(types)
    val generatedTypes = new java.util.HashSet[String]()
    @tailrec def generate(remaining: List[TypeDeclaration], built: Map[String, Tree]): Map[String, Tree] = {
      remaining match {
        case head :: tail =>
          head match {
            case a: ArrayTypeDeclaration =>
              a.items() match {
                case o: ObjectTypeDeclaration =>
                  val name = objectName(o, Some(a.name))
                  if (generatedTypes.add(name)) {
                    val klass = createObjectType(typeTable, name, o)
                    generate(o.properties().toList ::: tail, built + (name -> BLOCK(klass).inPackage(pkg)))
                  } else {
                    generate(o.properties().toList ::: tail, built)
                  }
                case s: StringTypeDeclaration if !s.enumValues().isEmpty =>
                  val name = enumName(s, Some(a.name))
                  if (generatedTypes.add(name)) {
                    generate(tail, built + (name -> BLOCK(createEnumType(typeTable, name, s)).inPackage(pkg)))
                  } else {
                    generate(tail, built)
                  }
                case _ =>
                  generate(tail, built)
              }
            case o: ObjectTypeDeclaration =>
              val name = objectName(o)
              if (generatedTypes.add(name)) {
                val klass = createObjectType(typeTable, objectName(o), o)
                generate(o.properties().toList ::: tail, built + (name -> BLOCK(klass).inPackage(pkg)))
              } else {
                generate(o.properties.toList ::: tail, built)
              }
            case s: StringTypeDeclaration if !s.enumValues().isEmpty =>
              val name = enumName(s)
              if (generatedTypes.add(name)) {
                generate(tail, built + (name -> BLOCK(createEnumType(typeTable, s.name(), s)).inPackage(pkg)))
              } else {
                generate(tail, built)
              }
            case _ =>
              generate(tail, built)
          }
        case Nil =>
          built
      }
    }
    generateBuiltInTypes(pkg) ++ generate(types.toList, Map.empty)
  }

  def generateBuiltInTypes(pkg: String): Map[String, Tree] = {
    val OffsetDateTime = RootClass.newClass("java.time.OffsetDateTime")
    val LocalTime = RootClass.newClass("java.time.LocalTime")
    val LocalDate = RootClass.newClass("java.time.LocalDate")
    val LocalDateTime = RootClass.newClass("java.time.LocalDateTime")
    val DateTimeFormatter = RootClass.newClass("java.time.format.DateTimeFormatter")

    val jsonProtocolTrait = TRAITDEF("RamlJsonProtocol") withParents("spray.json.DefaultJsonProtocol", "play.api.libs.json.DefaultReads", "play.api.libs.json.DefaultWrites", "play.api.libs.json.DefaultFormat") := BLOCK(
      VAL("dateTimeFormat") := REF(DateTimeFormatter) DOT "ISO_OFFSET_DATE_TIME",
      VAL("timeFormat") := REF(DateTimeFormatter) DOT "ISO_LOCAL_TIME",
      VAL("dateFormat") := REF(DateTimeFormatter) DOT "ISO_LOCAL_DATE",
      VAL("dateTimeOnlyFormat") := REF(DateTimeFormatter) DOT "ISO_LOCAL_DATE_TIME",
      OBJECTDEF("OffsetDateTimeJsonFormat") withParents JSON_FORMAT(OffsetDateTime) withFlags Flags.IMPLICIT := BLOCK(
        DEF("read", OffsetDateTime) withParams PARAM("value", JsValue) := {
          REF("value") MATCH(
            CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF(OffsetDateTime) DOT "parse" APPLY(REF("s"), REF("dateTimeOnlyFormat"))),
            CASE(WILDCARD) ==>  (REF("spray.json.deserializationError") APPLY LIT(s"Expected offset date time (e.g. 2007-12-03T10:15:30+01:00)"))
            )
        },
        DEF("write", JsValue) withParams PARAM("time", OffsetDateTime) := {
          REF(JsString) APPLY(REF("time") DOT "format" APPLY REF("dateTimeFormat"))
        }
      ),
      OBJECTDEF("LocalTimeJsonFormat") withParents JSON_FORMAT(LocalTime) withFlags Flags.IMPLICIT := BLOCK(
        DEF("read", LocalTime) withParams PARAM("value", JsValue) := {
          REF("value") MATCH(
            CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF(LocalTime) DOT "parse" APPLY(REF("s"), REF("dateTimeOnlyFormat"))),
            CASE(WILDCARD) ==>  (REF("spray.json.deserializationError") APPLY LIT(s"Expected local time (e.g. 10:15:30)"))
            )
        },
        DEF("write", JsValue) withParams PARAM("time", LocalTime) := {
          REF(JsString) APPLY(REF("time") DOT "format" APPLY REF("timeFormat"))
        }
      ),
      OBJECTDEF("LocalDateJsonFormat") withParents JSON_FORMAT(LocalDate) withFlags Flags.IMPLICIT := BLOCK(
        DEF("read", LocalDate) withParams PARAM("value", JsValue) := {
          REF("value") MATCH(
            CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF(LocalDate) DOT "parse" APPLY(REF("s"), REF("dateTimeOnlyFormat"))),
            CASE(WILDCARD) ==>  (REF("spray.json.deserializationError") APPLY LIT(s"Expected local date (e.g. 2007-12-03)"))
            )
        },
        DEF("write", JsValue) withParams PARAM("time", LocalDate) := {
          REF(JsString) APPLY(REF("time") DOT "format" APPLY REF("timeFormat"))
        }
      ),
      OBJECTDEF("LocalDateTimeJsonFormat") withParents JSON_FORMAT(LocalDateTime) withFlags Flags.IMPLICIT := BLOCK(
        DEF("read", LocalDateTime) withParams PARAM("value", JsValue) := {
          REF("value") MATCH(
            CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF(LocalDateTime) DOT "parse" APPLY(REF("s"), REF("dateTimeOnlyFormat"))),
            CASE(WILDCARD) ==>  (REF("spray.json.deserializationError") APPLY LIT(s"Expected local date (e.g. 2007-12-03T10:15:30)"))
            )
        },
        DEF("write", JsValue) withParams PARAM("time", LocalDateTime) := {
          REF(JsString) APPLY(REF("time") DOT "format" APPLY REF("timeFormat"))
        }
      )
    )
    val jsonProtocolObj = OBJECTDEF("RamlJsonProtocol") withParents "RamlJsonProtocol"

    Map("RamlJsonProtocol" -> BLOCK(jsonProtocolTrait, jsonProtocolObj).inPackage(pkg))
  }
}