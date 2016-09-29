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

  val TryClass = RootClass.newClass("scala.util.Try")

  val SeqClass = RootClass.newClass("scala.collection.immutable.Seq")

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
  val PlayWrites = RootClass.newClass("play.api.libs.json.Writes")

  def camelify(name: String): String = name.toLowerCase.capitalize

  def underscoreToCamel(name: String) = "(_|\\,)([a-z\\d])".r.replaceAllIn(name, { m =>
    m.group(2).toUpperCase()
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
    override def toString: String = s"Enum($name, $values)"

    override def toTree(): Seq[Tree] = {
      val baseTrait = TRAITDEF(name) withParents("Product", "Serializable", "RamlGenerated") withFlags Flags.SEALED := BLOCK(
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
        enumObjects ++ Seq(playJsonFormat)
      )
      Seq(baseTrait.withDoc(comments), obj)
    }
  }

  case class FieldT(name: String, `type`: Type, comments: Seq[String], required: Boolean, default: Option[String], repeated: Boolean = false) {
    override def toString: String = s"$name: ${`type`}"

    lazy val param: treehugger.forest.ValDef = {
      if (required || default.isDefined) {
        defaultValue.fold { PARAM(name, `type`).tree } { d => PARAM(name, `type`) := d }
      } else {
        if (repeated) {
          if (`type`.toString().startsWith("Map")) {
            PARAM(name, `type`) := REF("Map") DOT "empty"
          } else {
            PARAM(name, `type`) := NIL
          }
        } else {
          PARAM(name, TYPE_OPTION(`type`)) := NONE
        }
      }
    }

    lazy val comment: String = if (comments.nonEmpty) {
      val lines = comments.flatMap(_.lines)
      s"@param $name ${lines.head} ${if (lines.tail.nonEmpty) "\n  " else ""}${lines.tail.mkString("\n  ")}"
    } else {
      ""
    }

    val defaultValue = default.map { d =>
      `type`.toString() match {
        case "Byte" => LIT(d.toByte)
        case "Short" => LIT(d.toShort)
        case "Int" => LIT(d.toInt)
        case "Long" => LIT(d.toLong)
        case "Float" => LIT(d.toFloat)
        case "Double" => LIT(d.toDouble)
        case "String" => LIT(d)
        // hopefully this is actually an enum
        case t => (`type` DOT underscoreToCamel(camelify(d))).tree
      }
    }

    val playReader = {
      // required fields never have defaults
      if (required) {
        TUPLE(REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`
      } else if (repeated) {
        TUPLE(REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type` DOT "orElse" APPLY(REF(PlayReads) DOT "pure" APPLY(`type` APPLY()))
      } else {
        if (defaultValue.isDefined) {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "read" APPLYTYPE `type`) DOT "orElse" APPLY (REF(PlayReads) DOT "pure" APPLY defaultValue.get)
        } else {
          TUPLE((REF("__") DOT "\\" APPLY LIT(name)) DOT "readNullable" APPLYTYPE `type`)
        }
      }
    }

    val playValidator = {
      if (required) {
        REF("json") DOT "\\" APPLY LIT(name) DOT "validate" APPLYTYPE `type`
      } else if (repeated) {
        REF("json") DOT "\\" APPLY LIT(name) DOT "validate" APPLYTYPE `type` DOT "orElse" APPLY (REF(PlayJsSuccess) APPLY(`type` APPLY()))
      } else {
        if (defaultValue.isDefined) {
          (REF("json") DOT "\\" APPLY LIT(name)) DOT "validate" APPLYTYPE `type` DOT "orElse" APPLY (REF(PlayJsSuccess) APPLY defaultValue.get)
        } else {
          (REF("json") DOT "\\" APPLY LIT(name)) DOT "validateOpt" APPLYTYPE `type`
        }
      }
    }
  }

  case class ObjectT(name: String, fields: Seq[FieldT], parentType: Option[String], comments: Seq[String], childTypes: Seq[ObjectT] = Nil, discriminator: Option[String] = None, discriminatorValue: Option[String] = None) extends GeneratedClass {
    override def toString: String = parentType.fold(s"$name(${fields.mkString(", ")})")(parent => s"$name(${fields.mkString(" , ")}) extends $parent")

    override def toTree(): Seq[Tree] = {
      val actualFields = fields.filter(_.name != discriminator.getOrElse(""))
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
          IMPORT("play.api.libs.functional.syntax._"),
          VAL("playJsonReader") withFlags Flags.IMPLICIT := TUPLE(
            if (actualFields.size > 1) {
              Seq(actualFields.map(_.playReader).reduce(_ DOT "and" APPLY _))
            } else {
              actualFields.map(_.playReader)
            }
          ) APPLY (REF(name) DOT "apply _"),
          // TODO: Need discriminator...
          OBJECTDEF("playJsonWriter") withParents (PlayWrites APPLYTYPE name) withFlags Flags.IMPLICIT := BLOCK(
            DEF("writes", PlayJsObject) withParams PARAM("o", name) := {
              REF(PlayJson) DOT "obj" APPLY
                fields.map { field =>
                  if (field.name == discriminator.get) {
                    TUPLE(LIT(field.name), REF(PlayJson) DOT "toJsFieldJsValueWrapper" APPLY(PlayJson DOT "toJson" APPLY LIT(discriminatorValue.getOrElse(name))))
                  } else {
                    TUPLE(LIT(field.name), REF(PlayJson) DOT "toJsFieldJsValueWrapper" APPLY(PlayJson DOT "toJson" APPLY (REF("o") DOT field.name)))
                  }
                }
            }
          )
        )
      } else if (actualFields.nonEmpty && actualFields.exists(_.default.nonEmpty) && !actualFields.exists(_.repeated)) {
        Seq(
          IMPORT("play.api.libs.json._"),
          IMPORT("play.api.libs.functional.syntax._"),
          VAL("playJsonReader") withFlags Flags.IMPLICIT := TUPLE(
            actualFields.map(_.playReader).reduce(_ DOT "and" APPLY _)
          ) APPLY (REF(name) DOT "apply _"),
          VAL("playJsonWriter") withFlags Flags.IMPLICIT := REF(PlayJson) DOT "writes" APPLYTYPE (name)
        )
      } else if (actualFields.size > 22 || actualFields.exists(_.repeated) ||
        actualFields.map(_.toString).exists(t => t.toString().startsWith(name) || t.toString.contains(s"[$name]"))) {
        Seq(
          OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
            DEF("reads", PLAY_JSON_RESULT(name)) withParams PARAM("json", PlayJsValue) := BLOCK(
              actualFields.map { field =>
                VAL(field.name) := field.playValidator
              } ++ Seq(
                VAL("_errors") := SEQ(actualFields.map(f => REF(f.name))) DOT "collect" APPLY BLOCK(CASE(REF(s"e:$PlayJsError")) ==> REF("e")),
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
              actualFields.map { field =>
                VAL(field.name) := REF(PlayJson) DOT "toJson" APPLY (REF("o") DOT field.name)
              } ++
                Seq(
                  REF(PlayJsObject) APPLY SEQ(
                    actualFields.map { field =>
                      TUPLE(LIT(field.name), REF(field.name))
                    })
                )
            )
          )
        )
      } else {
        Seq(VAL("playJsonFormat") withFlags Flags.IMPLICIT := REF("play.api.libs.json.Json") DOT "format" APPLYTYPE (name))
      }

      val obj = if (childTypes.isEmpty) {
        (OBJECTDEF(name)) := BLOCK(
          playFormat
        )
      } else if (discriminator.isDefined) {
        val childDiscriminators: Map[String, ObjectT] = childTypes.map(ct => ct.discriminatorValue.getOrElse(ct.name) -> ct)(collection.breakOut)
        OBJECTDEF(name) := BLOCK(
          OBJECTDEF("PlayJsonFormat") withParents PLAY_JSON_FORMAT(name) withFlags Flags.IMPLICIT := BLOCK(
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
                  CASE(REF(s"f:${v.name}")) ==> (REF(PlayJson) DOT "toJson" APPLY REF("f") APPLY(REF(v.name) DOT "playJsonWriter"))
                }
            )
          )
        )
      } else {
        System.err.println(s"[WARNING] $name uses subtyping but has no discriminator. If it is not a union type when it is" +
          " used, it will not be able to be deserialized at this time")
        OBJECTDEF(name).tree
      }

      val commentBlock = (comments ++ actualFields.map(_.comment)(collection.breakOut)).map { s =>
        s.replace("$", "$$")
      }
      Seq(klass.withDoc(commentBlock)) ++ childTypes.flatMap(_.toTree()) ++ Seq(obj)
    }
  }

  case class StringT(name: String) extends GeneratedClass {
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
            CASECLASSDEF(s.name) withParents name withParams PARAM("value", StringClass).tree,
            OBJECTDEF(s.name) := BLOCK(
              OBJECTDEF("playJsonFormat") withParents PLAY_JSON_FORMAT(s.name) withFlags Flags.IMPLICIT := BLOCK(
                DEF("reads", PLAY_JSON_RESULT(s.name)) withParams PARAM("json", PlayJsValue) := BLOCK(
                  REF("json") DOT "validate" APPLYTYPE StringClass DOT "map" APPLY (REF(s.name) DOT "apply")
                ),
                DEF("writes", PlayJsValue) withParams PARAM("o", s.name) := BLOCK(
                  REF(PlayJsString) APPLY (REF("o") DOT "value")
                )
              )
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
      def createField(field: TypeDeclaration): FieldT = {
        val comments = comment(field)
        val defaultValue = Option(field.defaultValue())
        // if a field has a default, its not required.
        val required = defaultValue.fold(Option(field.required()).fold(false)(_.booleanValue()))(_ => false)
        field match {
          case a: ArrayTypeDeclaration =>
            @tailrec def arrayType(name: String, a: ArrayTypeDeclaration, outerType: Type): FieldT = {
              a.items() match {
                case n: ArrayTypeDeclaration =>
                  arrayType(name, n, outerType TYPE_OF SeqClass)
                case o: ObjectTypeDeclaration =>
                  val typeName = objectName(o)._1
                  FieldT(name, outerType TYPE_OF typeName, comments, required, defaultValue, true)
                case t: TypeDeclaration =>
                  FieldT(name, outerType TYPE_OF typeTable(t.`type`().replaceAll("\\[\\]", "")), comments, required, defaultValue, true)
              }
            }
            arrayType(a.name(), a, SeqClass)
          case n: NumberTypeDeclaration =>
            FieldT(n.name(), typeTable(Option(n.format()).getOrElse("double")), comments, required, defaultValue)
          case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
            val valueType = o.properties.head.`type`()
            FieldT(o.name(), TYPE_MAP(StringClass, typeTable(valueType)), comments, false, defaultValue, true)
          case t: TypeDeclaration =>
            FieldT(t.name(), typeTable(t.`type`()), comments, required, defaultValue)
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
                    StringT(s.name)
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
                val fields: Seq[FieldT] = o.properties().withFilter(_.`type`() != "nil").map(createField)(collection.breakOut)
                val objectType = ObjectT(name, fields, parent, comment(o), discriminator = Option(o.discriminator()), discriminatorValue = Option(o.discriminatorValue()))
                buildTypes(s.tail, results + objectType)
              } else {
                buildTypes(s.tail, results)
              }
            case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
              buildTypes(s.tail, results)
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
      .inPackage(pkg)
    Map("RamlGenerated" -> baseType)
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
