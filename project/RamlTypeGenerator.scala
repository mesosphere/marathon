package mesosphere.raml

import treehugger.forest._
import definitions._
import org.raml.v2.api.RamlModelResult
import org.raml.v2.api.model.v10.api.Library
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
  val JsObject = RootClass.newClass("spray.json.JsObject")
  val DefaultJsonProtocol = RootClass.newClass("RamlJsonProtocol")
  val SeqClass = RootClass.newClass("scala.collection.immutable.Seq")

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

  def camelify(name: String): String = name.toLowerCase.capitalize

  def underscoreToCamel(name: String) = "_([a-z\\d])".r.replaceAllIn(name, { m =>
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

    def toTree(): Seq[Tree]
  }

  case class EnumT(name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
    override def toString: String = s"Enum($name, $values)"

    override def toTree(): Seq[Tree] = {
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
        CASE(LIT(enumValue.toLowerCase)) ==> REF(underscoreToCamel(camelify(enumValue)))
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
        (REF(PlayJsError) APPLY (REF(PlayValidationError) APPLY(LIT("error.expected.jsstring"), LIT(s"$name (${values.mkString(", ")})"))))
      val playPatternMatches = values.map { enumValue =>
        CASE(LIT(enumValue.toLowerCase)) ==> (REF(PlayJsSuccess) APPLY REF(underscoreToCamel(camelify(enumValue))))
      }

      val playJsonFormat = (OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT) := BLOCK(
        DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := {
          REF("json") MATCH(
            CASE(REF(PlayJsString) UNAPPLY ID("s")) ==> (REF("s") DOT "toLowerCase" MATCH (playPatternMatches.toVector ++ Vector(playWildcard))),
            playWildcard)
        },
        DEF("writes", PlayJsValue) withParams PARAM("o", name) := {
          REF(PlayJsString) APPLY (REF("o") DOT "value")
        }
      )

      val obj = OBJECTDEF(name) := BLOCK(
        enumObjects ++ Seq(enumJsonFormat, playJsonFormat)
      )
      Seq(baseTrait.withDoc(comments), obj)
    }
  }

  case class FieldT(name: String, `type`: Type, comments: Seq[String], required: Boolean, default: Option[String], repeated: Boolean = false) {
    override def toString: String = s"$name: ${`type`}"

    lazy val param: treehugger.forest.ValDef = {
      if (required || default.isDefined) {
        PARAM(name, `type`).tree
      } else {
        if (repeated) {
          PARAM(name, `type`) := NIL
        } else {
          PARAM(name, TYPE_OPTION(`type`)) := NONE
        }
      }
    }

    lazy val comment: String = if (comments.nonEmpty) {
      val lines = comments.flatMap(_.lines)
      s"@$name ${lines.head} ${if (lines.tail.nonEmpty) "\n  " else ""}${lines.tail.mkString("\n  ")}"
    } else {
      ""
    }

    val defaultValue = default.map { d=>
      `type`.toString() match {
        case "Byte" => d.toByte
        case "Short" => d.toShort
        case "Int" => d.toInt
        case "Long" => d.toLong
        case "Float" => d.toFloat
        case "Double" => d.toDouble
        case "String" => d
        case t =>
          sys.error(s"Unable to understand the default of $name (${`type`}) - $d")
      }
    }.map { value => LIT(value) }

    def sprayReader(parent: String) = {
      // required fields never have defaults
      if (required) {
        VAL(name) := (REF("fields") DOT "get" APPLY(LIT(name)) DOT "map" APPLY (REF("_") DOT "convertTo" APPLYTYPE(`type`))) DOT "getOrElse" APPLY(THROW(REF("spray.json.deserializationError") APPLY LIT(s"$parent: missing required field: $name")))
      } else {
        defaultValue.fold {
          if (repeated) {
            VAL(name) := (REF("fields") DOT "getOrElse" APPLY((LIT(name) DOT "convertTo" APPLYTYPE(`type`)), NIL))
          } else {
            VAL(name) := (REF("fields") DOT "get" APPLY(LIT(name)) DOT "map" APPLY(REF("_") DOT "convertTo" APPLYTYPE(`type`)))
          }
        } { defaultValue =>
          VAL(name) := (REF("fields") DOT "get" APPLY(LIT(name)) DOT "map" APPLY(REF("_") DOT "convertTo" APPLYTYPE(`type`)) DOT "getOrElse" APPLY defaultValue)
        }
      }
    }

    val playReader = {
      // required fields never have defaults
      if (required) {
        TUPLE(REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`
      } else {
        if (defaultValue.isDefined) {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`) DOT "orElse" APPLY(REF("Reads") DOT "pure" APPLY(defaultValue.get))
        } else {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "readNullable" APPLYTYPE`type`)
        }
      }
    }
  }

  case class ObjectT(name: String, fields: Seq[FieldT], parentType: Option[String], comments: Seq[String], childTypes: Seq[ObjectT] = Nil, discriminator: Option[String] = None, discriminatorValue: Option[String] = None) extends GeneratedClass {
    override def toString: String = parentType.fold(s"$name(${fields.mkString(", ")})")(parent => s"$name(${fields.mkString(" , ")}) extends $parent")

    override def toTree(): Seq[Tree] = {
      val params = fields.map(_.param)
      val klass = if (childTypes.nonEmpty) {
        parentType.fold(TRAITDEF(name) := BLOCK(params))(parent =>
          TRAITDEF(name) withParents parent := BLOCK(params)
        )
      } else {
        parentType.fold(CASECLASSDEF(name) withParams params)(parent =>
          CASECLASSDEF(name) withParams params withParents parent
        ).tree
      }

      val sprayFormat = if (fields.exists(_.default.nonEmpty)) {
        OBJECTDEF("SprayJsonFormat") withParents (ROOT_JSON_FORMAT(name)) withFlags Flags.IMPLICIT := BLOCK(
          IMPORT("spray.json._"),
          DEF("write", JsValue) withParams PARAM("o", name) := BLOCK(
            REF(JsObject) APPLY fields.map(f => TUPLE(LIT(f.name), REF("o") DOT f.name DOT "toJson"))
          ),
          DEF("read", name) withParams PARAM("json", JsValue) := BLOCK(
            Seq(VAL("fields") := (REF("json") DOT "asJsObject" DOT "fields")) ++
              fields.map(_.sprayReader(name)) ++
              Seq(REF(name) APPLY(fields.map(f => REF(f.name) := REF(f.name))))
          )
        )
      } else {
        VAL("sprayJsonFormat") withFlags Flags.IMPLICIT := REF(s"jsonFormat${fields.size}") APPLY(REF(name) DOT "apply")
      }

      val playFormat = if (fields.nonEmpty && fields.exists(_.default.nonEmpty)) {
        Seq(
          IMPORT("play.api.libs.json._"),
          IMPORT("play.api.libs.functional.syntax._"),
          VAL("playJsonReader") withFlags Flags.IMPLICIT := TUPLE(
            fields.map(_.playReader).reduce(_ DOT "and" APPLY(_))
          ) APPLY(REF(name) DOT "apply _"),
          VAL("playJsonWriter") withFlags Flags.IMPLICIT := REF("play.api.libs.json.Json") DOT "writes" APPLYTYPE(name)
        )
      } else {
        Seq(VAL("playJsonFormat") withFlags Flags.IMPLICIT := REF("play.api.libs.json.Json") DOT "format" APPLYTYPE(name))
      }

      val obj = if (childTypes.isEmpty) {
        (OBJECTDEF(name) withParents DefaultJsonProtocol) := BLOCK(
          sprayFormat +:
            playFormat
        )
      } else {
        if (discriminator.isEmpty)
          sys.error("Attempted to use subclassing without a discrimantor defined")
        val childDiscriminators: Map[String, ObjectT] = childTypes.map(ct => ct.discriminatorValue.getOrElse(ct.name) -> ct)(collection.breakOut)
        (OBJECTDEF(name) withParents DefaultJsonProtocol) := BLOCK(
          OBJECTDEF("SprayJsonFormat") withParents ROOT_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
            IMPORT("spray.json._"),
            DEF("read", name) withParams PARAM("json", JsValue) := BLOCK(
              REF("json") DOT "asJsObject" DOT "fields" DOT "get" APPLY(LIT(discriminator.get)) MATCH(
                childDiscriminators.map { case (k, v) =>
                  CASE(JsString APPLY LIT(k)) ==> (REF("json") DOT "convertTo" APPLYTYPE(v.name))
                } ++
                  Seq(CASE(WILDCARD) ==> THROW(REF("spray.json.deserializationError") APPLY LIT(s"$name: Unable to deserialize into any of the types: ${childTypes.map(_.name).mkString(", ")}")))
                )
            ),
            DEF("write", JsValue) withParams PARAM("o", name) := BLOCK(
              REF("o") MATCH
                childDiscriminators.map { case (k, v) =>
                  CASE(REF(s"f:${v.name}")) ==> (REF("f") DOT "toJson" APPLYTYPE(v.name))
                }
            )
          )
        )
      }

      val commentBlock = (comments ++ fields.map(_.comment)(collection.breakOut))
      Seq(klass.withDoc(commentBlock), obj) ++ childTypes.flatMap(_.toTree())
    }
  }

  case object StringT extends GeneratedClass {
    val name = "value"

    override def toTree(): Seq[treehugger.forest.Tree] = Seq.empty[Tree]
  }

  case class UnionT(name: String, childTypes: Seq[GeneratedClass], comments: Seq[String]) extends GeneratedClass {
    override def toString: String = s"Union($name, $childTypes)"

    override def toTree(): Seq[Tree] = {
      println(s"Union Type: $name (${childTypes.map(_.name)})")
      Seq.empty[Tree]
    }
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
        val defaultValue = Option(field.defaultValue())
        // if a field has a default, its not required.
        val required = defaultValue.fold(Option(field.required()).fold(false)(_.booleanValue()))(_ => false)
        field match {
          case a: ArrayTypeDeclaration =>
            a.items() match {
              case a: ArrayTypeDeclaration =>
                sys.error("Nested Arrays are not yet supported")
              case o: ObjectTypeDeclaration =>
                val typeName = objectName(o)._1
                FieldT(a.name(), TYPE_SEQ(typeTable(typeName)), comments, required, defaultValue, true)
              case t: TypeDeclaration =>
                FieldT(a.name(), TYPE_SEQ(typeTable(t.`type`().replaceAll("\\[\\]", ""))), comments, required, defaultValue, true)
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
              if (!results.exists(_.name == u.name())) {
                val subTypeNames = u.`type`().split("\\|").map(_.trim)
                val subTypeDeclarations = subTypeNames.flatMap { t => allTypes.find(_.name == t) }
                val subTypes = subTypeDeclarations.map {
                  case o: ObjectTypeDeclaration =>
                    val (name, parent) = objectName(o)
                    val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(createField)(collection.breakOut)
                    ObjectT(name, fields, parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
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
              if (!results.exists(_.name == o.name())) {
                val (name, parent) = objectName(o)
                val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(createField)(collection.breakOut)
                val objectType = ObjectT(name, fields, parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
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
    val childTypes = all.collect { case obj: ObjectT if obj.parentType.isDefined => obj }.groupBy(_.parentType.get)
    val childNames = childTypes.values.flatMap(_.map(_.name)).toSet

    val unionTypeNames = all.collect { case u: UnionT => u }.flatMap { t => t.childTypes.map(_.name) + t.name }
    all.withFilter(t => !unionTypeNames.contains(t.name) && !childNames.contains(t.name) && t != StringT).map {
      case obj: ObjectT if childTypes.containsKey(obj.name) =>
        val children = childTypes(obj.name)/*.map { child =>
          child.copy(fields = obj.fields ++ child.fields)
        }*/
        obj.copy(childTypes = children.to[Seq])
      case t => t
    }
  }

  def apply(models: Seq[RamlModelResult], pkg: String): Map[String, Tree] = {
    val typeDeclarations = allTypes(models)
    val typeTable = buildTypeTable(typeDeclarations)
    val types = buildTypes(typeTable, typeDeclarations)

    generateBuiltInTypes(pkg) ++ types.map { tpe =>
      val tree = tpe.toTree()
      if (tree.nonEmpty) {
        tpe.name -> BLOCK(tree).inPackage(pkg)
      } else {
        tpe.name -> BLOCK().withComment(s"Unsupported: $tpe").inPackage(pkg)
      }
    }(collection.breakOut)
  }
}
