package mesosphere.raml

import treehugger.forest._
import definitions._
import mesosphere.raml.backend._
import mesosphere.raml.backend.treehugger.{GeneratedFile, GeneratedObject, Visitor}
import mesosphere.raml.ir.{ConstraintT, EnumT, FieldT, GeneratedClass, ObjectT, StringT, NumberT, UnionT}
import org.raml.v2.api.RamlModelResult
import org.raml.v2.api.model.v10.api.Library
import org.raml.v2.api.model.v10.datamodel._
import treehuggerDSL._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object RamlTypeGenerator {

  def enumName(s: StringTypeDeclaration, default: Option[String] = None): String = {
    s.annotations().asScala.find(_.name() == "(pragma.scalaType)").fold(default.getOrElse(s.name()).capitalize) { annotation =>
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

  def isUpdateType(o: ObjectTypeDeclaration): Boolean =
    (o.`type`() == "object") && o.annotations.asScala.exists(_.name() == "(pragma.asUpdateType)")

  def isOmitEmpty(field: TypeDeclaration): Boolean =
    field.annotations.asScala.exists(_.name() == "(pragma.omitEmpty)")

  def pragmaForceOptional(o: TypeDeclaration): Boolean =
    o.annotations().asScala.exists(_.name() == "(pragma.forceOptional)")

  def pragmaSerializeOnly(o: TypeDeclaration): Boolean =
    o.annotations().asScala.exists(_.name() == "(pragma.serializeOnly)")

  def generateUpdateTypeName(o: ObjectTypeDeclaration): Option[String] =
    if (o.`type`() == "object" && !isUpdateType(o)) {
      // use the attribute value as the type name if specified ala enumName; otherwise just append "Update"
      o.annotations().asScala.find(_.name() == "(pragma.generateUpdateType)").map { annotation =>
        Option(annotation.structuredValue().value()).fold(o.name() + "Update")(_.toString)
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
            case e: StringTypeDeclaration if e.enumValues().asScala.nonEmpty =>
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

  @tailrec def libraryTypes(libraries: List[Library], result: Set[TypeDeclaration] = Set.empty): Set[TypeDeclaration] = {
    libraries match {
      case head :: tail =>
        libraryTypes(head.uses.asScala.toList ::: tail, result ++ head.types().asScala.toSet)
      case Nil =>
        result
    }
  }

  @tailrec def allTypes(models: Seq[RamlModelResult], result: Set[TypeDeclaration] = Set.empty): Set[TypeDeclaration] = {
    models match {
      case head +: tail =>
        val types = libraryTypes(Option(head.getLibrary).toList) ++
          libraryTypes(Option(head.getApiV10).map(_.uses().asScala.toList).getOrElse(Nil))
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
        Seq(
          escapeDesc(Option(a.description()).map(_.value)),
          Option(a.minItems()).map(i => s"minItems: $i"),
          Option(a.maxItems()).map(i => s"maxItems: $i")
        ).flatten
      case o: ObjectTypeDeclaration =>
        Seq(escapeDesc(Option(o.description()).map(_.value)), Option(o.example()).map(e => s"Example: <pre>${e.value}</pre>")).flatten
      case s: StringTypeDeclaration =>
        Seq(
          escapeDesc(Option(s.description()).map(_.value)),
          Option(s.maxLength()).map(i => s"maxLength: $i"),
          Option(s.minLength()).map(i => s"minLength: $i"),
          Option(s.pattern()).map(i => s"pattern: <pre>$i</pre>")
        ).flatten
      case n: NumberTypeDeclaration =>
        Seq(
          escapeDesc(Option(n.description()).map(_.value)),
          Option(n.minimum()).map(i => s"minimum: $i"),
          Option(n.maximum()).map(i => s"maximum: $i"),
          Option(n.multipleOf()).map(i => s"multipleOf: $i")
        ).flatten
      case _ =>
        Seq(escapeDesc(Option(t.description()).map(_.value()))).flatten
    }
  }

  def typeIsActuallyAMap(t: TypeDeclaration): Boolean =
    t match {
      case o: ObjectTypeDeclaration =>
        o.properties.asScala.toList match {
          case field :: Nil if field.name().startsWith('/') && field.name().endsWith('/') => true
          case _ => false
        }
      case _ => false
    }

  def buildTypes(typeTable: Map[String, Symbol], allTypes: Set[TypeDeclaration]): Set[GeneratedClass] = {
    @tailrec def buildTypes(types: Set[TypeDeclaration], results: Set[GeneratedClass] = Set.empty[GeneratedClass]): Set[GeneratedClass] = {
      def buildConstraints(field: TypeDeclaration, fieldType: Type): Seq[ConstraintT[_]] = {
        Option(field).collect {
          case s: StringTypeDeclaration =>
            Seq(
              Option(s.maxLength()).map(ConstraintT.MaxLength(_)),
              Option(s.minLength()).map(ConstraintT.MinLength(_)),
              Option(s.pattern()).map(ConstraintT.Pattern(_))
            ).flatten
          case a: ArrayTypeDeclaration =>
            Seq(
              Option(a.maxItems()).map(len => ConstraintT.MaxItems(len, fieldType)),
              Option(a.minItems()).map(len => ConstraintT.MinItems(len, fieldType))
            ).flatten
          case n: NumberTypeDeclaration =>
            // convert numbers so that constraints are appropriately rendered
            def toNum(v: Double): Number =
              fieldType match {
                case DoubleClass => v
                case FloatClass => v.toFloat
                case LongClass => v.toLong
                case _ => v.toInt
              }

            Seq(
              Option(n.maximum()).map(v => ConstraintT.Max(toNum(v), fieldType)),
              Option(n.minimum()).map(v => ConstraintT.Min(toNum(v), fieldType))
            ).flatten
          case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
            // last field of map-types has the pattern-matching spec that defines the key space, see typeIsActuallyAMap
            val pattern = o.properties.asScala.last.name
            val valueType = typeTable(o.properties.asScala.last.`type`)
            if (pattern != "/.*/" && pattern != "/^.*$/") {
              Seq(ConstraintT.KeyPattern(pattern.substring(1, pattern.length() - 1), valueType))
            } else Nil
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
        require(
          !(((required || defaultValue.nonEmpty) && !forceOptional) && omitEmpty),
          s"field $fieldOwner.${field.name()} specifies omitEmpty but is required or provides a default value"
        )

        def arrayType(a: ArrayTypeDeclaration): Type =
          if (scala.util.Try[Boolean](a.uniqueItems()).getOrElse(false)) SetClass else backend.SeqClass

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
            FieldT(
              a.name(),
              finalType,
              comments,
              buildConstraints(field, finalType),
              required,
              defaultValue,
              repeated = true,
              forceOptional = forceOptional,
              omitEmpty = omitEmpty
            )
          case n: NumberTypeDeclaration =>
            val fieldType = typeTable(Option(n.format()).getOrElse("double"))
            FieldT(
              n.name(),
              fieldType,
              comments,
              buildConstraints(field, fieldType),
              required,
              defaultValue,
              forceOptional = forceOptional,
              omitEmpty = omitEmpty
            )
          case o: ObjectTypeDeclaration if typeIsActuallyAMap(o) =>
            val fieldType = o.properties.asScala.head match {
              case n: NumberTypeDeclaration =>
                TYPE_MAP(StringClass, typeTable(Option(n.format()).getOrElse("double")))
              case t =>
                TYPE_MAP(StringClass, typeTable(t.`type`()))
            }
            val constraints = buildConstraints(o, fieldType)
            FieldT(
              o.name(),
              fieldType,
              comments,
              constraints,
              required = false,
              defaultValue,
              repeated = true,
              forceOptional = forceOptional,
              omitEmpty = omitEmpty
            )
          case t: TypeDeclaration =>
            val (name, fieldType) = if (t.`type`() != "object") {
              t.name() -> typeTable(t.`type`())
            } else {
              AdditionalProperties -> PlayJsObject
            }
            FieldT(
              name,
              fieldType,
              comments,
              buildConstraints(field, fieldType),
              required,
              defaultValue,
              forceOptional = forceOptional,
              omitEmpty = omitEmpty
            )
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
                    val fields: Seq[FieldT] =
                      o.properties.asScala.withFilter(_.`type`() != "nil").map(f => createField(name, f))(collection.breakOut)
                    ObjectT(
                      name,
                      fields,
                      parent,
                      comment(o),
                      discriminator = Option(o.discriminator()),
                      discriminatorValue = Option(o.discriminatorValue()),
                      serializeOnly = pragmaSerializeOnly(o)
                    )
                  case s: StringTypeDeclaration =>
                    StringT(s.name, Option(s.defaultValue()))
                  case n: NumberTypeDeclaration =>
                    NumberT(n.name, Option(n.defaultValue()).map(_.toDouble))
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
                val fields: Seq[FieldT] =
                  o.properties().asScala.withFilter(_.`type`() != "nil").map(f => createField(name, f))(collection.breakOut)
                if (isUpdateType(o)) {
                  val objectType = ObjectT(
                    name,
                    fields.map(_.copy(forceOptional = true)),
                    parent,
                    comment(o),
                    discriminator = Option(o.discriminator()),
                    discriminatorValue = Option(o.discriminatorValue()),
                    serializeOnly = pragmaSerializeOnly(o)
                  )
                  buildTypes(s.tail, results + objectType)
                } else {
                  val objectType = ObjectT(
                    name,
                    fields,
                    parent,
                    comment(o),
                    discriminator = Option(o.discriminator()),
                    discriminatorValue = Option(o.discriminatorValue()),
                    serializeOnly = pragmaSerializeOnly(o)
                  )
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
            case e: StringTypeDeclaration if e.enumValues().asScala.nonEmpty =>
              val enumType = EnumT(e.name(), e.enumValues().asScala.toSet, Option(e.defaultValue()), comment(e))
              buildTypes(s.tail, results + enumType)
            case _ =>
              buildTypes(s.tail, results)
          }
        case _ =>
          results
      }
    }
    val all = buildTypes(allTypes)
    val childTypes: Map[String, Set[ObjectT]] =
      all.collect { case obj: ObjectT if obj.parentType.isDefined => obj }.groupBy(_.parentType.get)
    val childNames = childTypes.values.flatMap(_.map(_.name)).toSet

    val unionTypeNames = all.collect { case u: UnionT => u }.flatMap { t => t.childTypes.map(_.name) }

    // Reduce the list so that union types and type hierarchies are now all included from just the top-level type
    val filterPhase1 =
      all.withFilter(t => !unionTypeNames.contains(t.name) && !childNames.contains(t.name) && !t.isInstanceOf[StringT]).map {
        case u: UnionT =>
          val children = u.childTypes.map {
            case o: ObjectT =>
              o.copy(parentType = Some(u.name))
            case t => t
          }
          u.copy(childTypes = children)
        case obj: ObjectT if childTypes.contains(obj.name) =>
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

  /**
    * Generate a marker trait indicating generated code
    */
  private[this] def generateBaseType(pkg: String): PackageDef = {
    TRAITDEF("RamlGenerated").tree
      .withDoc("Marker trait indicating generated code.")
      .inPackage(pkg)
  }

  /**
    * Generates a validation helper for generated RAML code
    */
  private[this] def generateRamlConstraints(pkg: String): PackageDef = {
    BLOCK(
      (TRAITDEF("RamlConstraints") := BLOCK(
        DEF("keyPattern")
          withTypeParams (TYPEVAR(RootClass.newAliasType("T")))
          withParams (
            PARAM("regex", "=> scala.util.matching.Regex"),
            PARAM("error", StringClass) := LIT("error.pattern")
        )
          withParams (
            PARAM(
              "reads",
              PLAY_JSON_READS("Map[String,T]")
            )
          ).withFlags(Flags.IMPLICIT) := PLAY_JSON_READS("Map[String,T]").APPLY(
          LAMBDA(PARAM("js")) ==> BLOCK(
            ((REF("reads") DOT "reads") APPLY (REF("js")) DOT "flatMap").APPLY(
              LAMBDA(PARAM("m")) ==> BLOCK(
                VAL("errors") := (REF("m") DOT "map" APPLY (BLOCK(
                  CASE(TUPLE(REF("o"), WILDCARD)) ==>
                    (((REF("regex") DOT "unapplySeq") APPLY REF("o")) DOT "map" APPLY (
                      LAMBDA(PARAM(WILDCARD)) ==> (PlayJsSuccess APPLY REF("o"))
                    )) DOT "getOrElse" APPLY (PlayJsError APPLY (PlayPath DOT "\\" APPLY (REF("o")), PlayValidationError APPLY (REF(
                    "error"
                  ), REF("regex") DOT "regex")))
                ))) DOT "collect" APPLY (BLOCK(
                  CASE(ID("err") withType (PlayJsError)) ==> REF("err")
                )),
                IF(REF("errors") DOT "isEmpty") THEN (PlayJsSuccess APPLY (REF("m")))
                  ELSE (REF("errors") DOT "fold" APPLY (PlayJsError APPLY (REF("Nil"))) APPLY (WILDCARD DOT "++" APPLY WILDCARD))
              )
            )
          )
        )
      )).withDoc("Validation helpers for generated RAML code."),
      CASEOBJECTDEF("RamlConstraints").withParents("RamlConstraints").tree
    ).inPackage(pkg)
  }

  /**
    * Generate the global Raml Serializer for Jackson. Creates a new Jackson Object Serializer and registers the
    * generated serializers from each type, sets custom options.
    *
    * @param pkg The root package for generated code
    * @param generatedFiles A map of generated files, used to find all generated jackson serializers
    *
    * @return The generated treehugger model for the serializer
    */
  private[this] def generateRamlSerializer(pkg: String, generatedFiles: Map[GeneratedClass, GeneratedFile]): PackageDef = {
    BLOCK(
      IMPORT("com.fasterxml.jackson.databind.ObjectMapper"),
      IMPORT("com.fasterxml.jackson.databind.module.SimpleModule"),
      IMPORT("com.fasterxml.jackson.module.afterburner.AfterburnerModule"),
      IMPORT("com.fasterxml.jackson.module.scala.DefaultScalaModule"),
      IMPORT("com.fasterxml.jackson.databind.ser.std.StdSerializer"),
      OBJECTDEF("RamlSerializer") := BLOCK(
        PROC("addSerializer")
          .withTypeParams(TYPEVAR("T"))
          .withParams(
            VAL("classType", TYPE_REF("Class[T]")),
            VAL("s", TYPE_REF("StdSerializer[T]"))
          ) := BLOCK(
          REF("module") DOT "addSerializer" APPLY (REF("s"))
        ),
        // TODO: Make this private[this]
        VAL("module") := NEW("SimpleModule").APPLY(),
        VAL("serializer", "ObjectMapper") := BLOCK(
          Seq(VAL("mapper") := NEW("ObjectMapper").APPLY()) ++

            generatedFiles.values.flatMap(gf => gf.objects).collect {
              case GeneratedObject(name, _, Some(serializer)) =>
                val serializerRef = REF(serializer)
                val classOfMethod = PredefModuleClass.newMethod("classOf[" + name + "]")
                REF("module") DOT "addSerializer" APPLY (REF(classOfMethod), serializerRef)
            } ++

            Seq(REF("mapper") DOT "registerModule" APPLY REF("module")) ++
            Seq(REF("mapper") DOT "registerModule" APPLY REF("DefaultScalaModule")) ++
            Seq(REF("mapper") DOT "registerModule" APPLY NEW("AfterburnerModule"))
        ).withComment("ObjectMapper is thread safe, we have a single shared instance here")
      )
    ).inPackage(pkg)
  }

  /**
    * Generates common files, i.e. everything where we generate a single global file
    *
    * @param pkg The root package of the generated code
    * @param generatedFiles The files that were generated already, can be used to reference generated types
    *
    * @return A map of file base name (without extension) -> generated code as string
    */
  def generateBuiltInTypes(pkg: String, generatedFiles: Map[GeneratedClass, GeneratedFile]): Map[String, String] = {
    val baseType = generateBaseType(pkg)
    val ramlConstraints = generateRamlConstraints(pkg)
    val ramlSerializer = generateRamlSerializer(pkg, generatedFiles)

    Map(
      "RamlGenerated" -> treeToString(baseType),
      "RamlConstraints" -> treeToString(ramlConstraints),
      "RamlSerializer" -> treeToString(ramlSerializer)
    )
  }

  def apply(models: Seq[RamlModelResult], pkg: String): Map[String, String] = {
    // Front end: Parsed type declarations.
    val typeDeclarations = allTypes(models)

    // Middle end: Intermediate representation.
    val typeTable = buildTypeTable(typeDeclarations)
    val types = buildTypes(typeTable, typeDeclarations)

    // Back end: Code generation
    val files: Map[GeneratedClass, GeneratedFile] = types.map { gc => gc -> Visitor.visit(gc) } toMap

    val fileStrings: Map[String, String] = files.map { case (gc, gf) => (gc.name, gf.generateFile(pkg)) }

    fileStrings ++ generateBuiltInTypes(pkg, files)
  }
}
