package mesosphere.raml

import treehugger.forest._
import definitions._
import org.raml.v2.api.RamlModelResult
import org.raml.v2.api.model.v10.api.Library
import treehuggerDSL._
import org.raml.v2.api.model.v10.datamodel._

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.immutable.{Seq, SortedSet}

// scalastyle:off
object RamlTypeGenerator {
  val baseTypeTable: Map[String, Symbol] =
    Map(
      "string" -> StringClass,
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
      "datetime" -> RootClass.newClass("java.time.OffsetDateTime")
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
  val PlayValidationError = RootClass.newClass("play.api.data.validation.ValidationError")
  val PlayJsError = RootClass.newClass("play.api.libs.json.JsError")
  val PlayJsSuccess = RootClass.newClass("play.api.libs.json.JsSuccess")

  def TYPE_SEQ(typ: Type): Type = SeqClass TYPE_OF typ

  val PlayJsonFormat = RootClass.newClass("play.api.libs.json.Format")
  def PLAY_JSON_FORMAT(typ: Type): Type = PlayJsonFormat TYPE_OF typ
  val PlayJsonResult = RootClass.newClass("play.api.libs.json.JsResult")
  def PLAY_JSON_RESULT(typ: Type): Type = PlayJsonResult TYPE_OF typ
  val PlayJsValue = RootClass.newClass("play.api.libs.json.JsValue")
  val PlayJsString = RootClass.newClass("play.api.libs.json.JsString")
  val PlayJsArray = RootClass.newClass("play.api.libs.json.JsArray")
  val PlayValidationError = RootClass.newClass("play.api.data.validation.ValidationError")
  val PlayJsError = RootClass.newClass("play.api.libs.json.JsError")
  val PlayJsSuccess = RootClass.newClass("play.api.libs.json.JsSuccess")

  def camelify(name : String): String = name.toLowerCase.capitalize

  def underscoreToCamel(name: String) = "_([a-z\\d])".r.replaceAllIn(name, {m =>
    m.group(1).toUpperCase()
  })

  def enumName(s: StringTypeDeclaration, default: Option[String] = None): String = {
    s.annotations().find(_.name() == "(scalaType)").fold(default.getOrElse(s.name()).capitalize) { annotation =>
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


  def buildTypeTable(types: Set[TypeDeclaration]): Map[String, Symbol] = {
    @tailrec def build(types: Set[TypeDeclaration], result: Map[String, Symbol]): Map[String, Symbol] = {
      types match {
        case s if s.nonEmpty =>
          s.head match {
            case a: ArrayTypeDeclaration if a.items().`type`() != "string" =>
              sys.error(s"${a.name()} : ${a.items().name()} ${a.items.`type`} ArrayTypes should be declared as ObjectName[]")
            case o: ObjectTypeDeclaration =>
              val (name, _) = objectName(o)
              build(s.tail, result + (name -> RootClass.newClass(name)))
            case u: UnionTypeDeclaration =>
              build(s.tail, result + (u.name() -> RootClass.newClass(u.name)))
            case e: StringTypeDeclaration if e.enumValues().nonEmpty =>
              build(s.tail, result + (e.name -> RootClass.newClass(e.name)))
            case str: StringTypeDeclaration =>
              build(s.tail, result + (str.name -> StringClass))
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
    def toTree(pkg: String): Tree
  }

  case class EnumT(name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
    override def toString: String = s"Enum($name, $values)"

    override def toTree(pkg: String): Tree = {
      val baseTrait = TRAITDEF(name) withFlags Flags.SEALED := BLOCK(
        VAL("value", StringClass),
        DEF("toString", StringClass) withFlags Flags.OVERRIDE := REF("value")
      )

      val enumObjects = values.map { enumValue =>
        CASEOBJECTDEF(underscoreToCamel(camelify(enumValue))) withParents name := BLOCK(
          VAL("value") := LIT(enumValue.toLowerCase)
        )
      }.toVector

      val patternMatches = values.map { enumValue =>
        CASE (LIT (enumValue.toLowerCase)) ==> REF(underscoreToCamel(camelify(enumValue)))
      }

      val wildcard = CASE(WILDCARD) ==>
        (REF("spray.json.deserializationError") APPLY LIT(s"Expected $name (${values.mkString(", ")})"))

      val enumJsonFormat = (OBJECTDEF(s"jsonFormat") withParents JSON_FORMAT(name) withFlags Flags.IMPLICIT) := BLOCK(
        DEF("read", name) withParams PARAM("value", JsValue) := {
          REF("value") MATCH(
            CASE(REF(JsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH (patternMatches + wildcard)),
            wildcard
            )
        },
        DEF("write", JsValue) withParams PARAM("o", name) := {
          REF(JsString) APPLY (REF("o") DOT "value")
        }
      )

      val playWildcard = CASE(WILDCARD) ==>
        (REF(PlayJsError) APPLY (REF(PlayValidationError) APPLY (LIT("error.expected.jsstring"), LIT(s"$name (${values.mkString(", ")})"))))
      val playPatternMatches = values.map { enumValue =>
        CASE (LIT (enumValue.toLowerCase)) ==> (REF(PlayJsSuccess) APPLY REF(underscoreToCamel(camelify(enumValue))))
      }

      val playJsonFormat = (OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT) := BLOCK(
        DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := {
          REF("json") MATCH(
            CASE(REF(PlayJsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH(playPatternMatches.toVector ++ Vector(playWildcard))),
            playWildcard)
        },
        DEF("writes", PlayJsValue) withParams PARAM("o", name) := {
          REF(PlayJsString) APPLY (REF("o") DOT "value")
        }
      )

      val obj = OBJECTDEF(name) := BLOCK(
        enumObjects ++ Seq(enumJsonFormat, playJsonFormat)
      )
      BLOCK(baseTrait.withDoc(comments), obj).inPackage(pkg)
    }
  }

  case class FieldT(name: String, `type`: Type, comments: Seq[String], required: Boolean, default: Option[String], repeated: Boolean = false) {
    override def toString: String = s"$name: ${`type`}"
  }

  case class ObjectT(name: String, fields: Seq[FieldT], parentType: Option[String], comments: Seq[String], hasChildren: Boolean = false) extends GeneratedClass {
    override def toString: String = parentType.fold(s"$name(${fields.mkString(", ")})")(parent => s"$name(${fields.mkString(" , ")}) extends $parent")

    override def toTree(pkg: String): treehugger.forest.Tree = {
      val params = fields.map { field =>
        if (field.required) {
          PARAM(field.name, field.`type`).tree
        } else {
          if (field.repeated) {
            PARAM(field.name, field.`type`) := NIL
          } else {
            PARAM(field.name, TYPE_OPTION(field.`type`)) := NONE
          }
        }
      }

      val klass = if (hasChildren) {
        parentType.fold(TRAITDEF(name) withParams params)(parent =>
          TRAITDEF(name) withParams params withParents parent
        )
      } else {
        parentType.fold(CASECLASSDEF(name) withParams params)(parent =>
          CASECLASSDEF(name) withParams params withParents parent
        )
      }

      BLOCK(Seq(klass)).inPackage(pkg)
    }
  }

  case object StringT extends GeneratedClass {
    val name = "value"

    override def toTree(pkg: String): treehugger.forest.Tree = BLOCK().withoutPackage
  }

  case class UnionT(name: String, childTypes: Seq[GeneratedClass], comments: Seq[String]) extends GeneratedClass {
    override def toString: String = s"Union($name, $childTypes)"

    override def toTree(pkg: String): treehugger.forest.Tree = BLOCK().withoutPackage
  }

  /*
  def createObjectType(typeTable: Map[String, Symbol], name: String, objectType: ObjectTypeDeclaration): List[Tree] = {
    def paramDef(name: String, param: TypeDeclaration): Type = {
      param match {
        case s: StringTypeDeclaration if !s.enumValues.isEmpty =>
          typeTable(enumName(s, Some(name)))
        case a: ArrayTypeDeclaration =>
          TYPE_SEQ(paramDef(a.name, a.items))
        case o: ObjectTypeDeclaration =>
          typeTable(objectName(o)._1)
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
          PARAM(a.name, paramDef(a.name(), a)) := REF(SeqClass) DOT "empty"
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

    val obj = OBJECTDEF(name.capitalize) withParents DefaultJsonProtocol := BLOCK(
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
    val typeTable = buildTypeTable(types.toSet)
    val generatedTypes = new java.util.HashSet[String]()
    @tailrec def generate(remaining: List[TypeDeclaration], built: Map[String, Tree]): Map[String, Tree] = {
      remaining match {
        case head :: tail =>
          head match {
            case a: ArrayTypeDeclaration =>
              a.items() match {
                case o: ObjectTypeDeclaration =>
                  val name = objectName(o)
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

    val jsonProtocolTrait = TRAITDEF("RamlJsonProtocol") withParents("spray.json.DefaultJsonProtocol", "play.api.libs.json.DefaultReads", "play.api.libs.json.DefaultWrites") := BLOCK(
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
*/

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
    t match {
      case a: ArrayTypeDeclaration =>
        Seq(Option(a.description()).map(_.value),
          Option(a.minItems()).map(i => s"minItems: $i"),
          Option(a.maxItems()).map(i => s"maxItems: $i")).flatten
      case o: ObjectTypeDeclaration =>
        Seq(Option(o.description()).map(_.value),
          Option(o.example()).map(e => s"Example: $e")).flatten
      case s: StringTypeDeclaration =>
        Seq(Option(s.description()).map(_.value),
          Option(s.maxLength()).map(i => s"maxLength: $i"),
          Option(s.minLength()).map(i => s"minLength: $i"),
          Option(s.pattern()).map(i => s"pattern: $i")).flatten
      case n: NumberTypeDeclaration =>
        Seq(Option(n.description()).map(_.value),
          Option(n.minimum()).map(i => s"minimum: $i"),
          Option(n.maximum()).map(i => s"maximum: $i"),
          Option(n.multipleOf()).map(i => s"multipleOf: $i")).flatten
      case _ =>
        Seq(Option(t.description()).map(_.value())).flatten
    }
  }

  def buildTypes(typeTable: Map[String, Symbol], allTypes: Set[TypeDeclaration]): Set[GeneratedClass] = {
    @tailrec def buildTypes(types: Set[TypeDeclaration], results: Set[GeneratedClass] = Set.empty[GeneratedClass]): Set[GeneratedClass] = {
      def createField(field: TypeDeclaration): FieldT = {
        val comments = comment(field)
        val required = Option(field.required()).fold(false)(_.booleanValue())
        val defaultValue = Option(field.defaultValue())
        field match {
          case a: ArrayTypeDeclaration =>
            a.items() match {
              case a: ArrayTypeDeclaration =>
                sys.error("Nested Arrays are not yet supported")
              case o: ObjectTypeDeclaration =>
                val typeName = objectName(o)._1
                if (Option(a.uniqueItems()).fold(false)(_.booleanValue())) {
                  FieldT(a.name(), TYPE_SET(typeTable(typeName)), comments, required, defaultValue, true)
                } else {
                  FieldT(a.name(), TYPE_SEQ(typeTable(typeName)), comments, required, defaultValue, true)
                }
              case t: TypeDeclaration =>
                if (Option(a.uniqueItems()).fold(false)(_.booleanValue())) {
                  FieldT(a.name(), TYPE_SET(typeTable(t.`type`().replaceAll("\\[\\]", ""))), comments, required, defaultValue, true)
                } else {
                  FieldT(a.name(), TYPE_SEQ(typeTable(t.`type`().replaceAll("\\[\\]", ""))), comments, required, defaultValue, true)
                }
            }
          case n: NumberTypeDeclaration =>
            FieldT(n.name(), typeTable(Option(n.format()).getOrElse("double")), comments, required, defaultValue)
          case t: TypeDeclaration =>
            if (t.name().startsWith('/')) {
              FieldT("values", TYPE_MAP(StringClass, typeTable(t.`type`())), comments, required, defaultValue)
            } else {
              FieldT(t.name(), typeTable(t.`type`()), comments, required, defaultValue)
            }
        }
      }

      types match {
        case s if s.nonEmpty =>
          s.head match {
            case a: ArrayTypeDeclaration if a.items().`type`() != "string" =>
              sys.error("Can't build array types")
            case u: UnionTypeDeclaration =>
              if (results.find(_.name == u.name()).isEmpty) {
                val subTypeNames = u.`type`().split("\\|").map(_.trim)
                val subTypeDeclarations = subTypeNames.flatMap { t => allTypes.find(_.name == t) }
                val subTypes = subTypeDeclarations.map {
                  case o: ObjectTypeDeclaration =>
                    val (name, parent) = objectName(o)
                    val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(createField)(collection.breakOut)
                    ObjectT(name, fields, parent, comment(o))
                  case s: StringTypeDeclaration =>
                    StringT
                  case t =>
                    sys.error(s"Unable to generate union types of non-object/string subtypes: ${u.name()} ${t.name()} ${t.`type`()}")
                }
                val unionType = UnionT(u.name(), subTypes.toVector, comment(u))
                buildTypes(s.tail, results + unionType ++ subTypes)
              } else {
                buildTypes(s.tail, results)
              }
            case o: ObjectTypeDeclaration =>
              if (results.find(_.name == o.name()).isEmpty) {
                val (name, parent) = objectName(o)
                val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(createField)(collection.breakOut)
                val objectType = ObjectT(name, fields, parent, comment(o))
                buildTypes(s.tail, results + objectType)
              } else {
                buildTypes(s.tail, results)
              }
            case e: StringTypeDeclaration if e.enumValues().nonEmpty =>
              val enumType = EnumT(e.name(),
                e.enumValues().map(_.toLowerCase)(collection.breakOut),
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
    val parentTypes = all.collect { case obj: ObjectT if obj.parentType.isDefined => obj }.flatMap(_.parentType)
    all.map {
      case obj: ObjectT if parentTypes.contains(obj.name) =>
        obj.copy(hasChildren = true)
      case t => t
    }
  }

  def apply(models: Seq[RamlModelResult], pkg: String): Map[String, Tree] = {
    val typeDeclarations = allTypes(models)
    val typeTable = buildTypeTable(typeDeclarations)
    val types = buildTypes(typeTable, typeDeclarations)

    types.map{ tpe =>
      tpe.name -> tpe.toTree(pkg)
    }(collection.breakOut)
  }
}
