package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.{PlayPath, camelify, scalaFieldName, underscoreToCamel}
import mesosphere.raml.ir.{ConstraintT, FieldT}
import treehugger.forest._
import definitions._
import treehuggerDSL._

import scala.annotation.tailrec

object FieldVisitor {

  def visit(params: Seq[FieldT], flags: Seq[Long] = Seq()): Seq[ValDef] = {
    params.map { param =>
      param.paramTypeValue.fold { VAL(param.name, param.`type`).withFlags(flags: _*).tree } {
        case (pType, pValue) => VAL(param.name, pType).withFlags(flags: _*) := pValue
      }
    }
  }

  def visitForDef(params: Seq[FieldT]): Seq[DefDef] = {
    params.map { param =>
      param.paramTypeValue.fold { DEF(param.name, param.`type`).tree } { case (pType, pValue) => DEF(param.name, pType) := pValue }
    }
  }

  def playValidator(field: FieldT) = {
    def reads = validateConstraints(field.constraints)(PlayPath DOT "read" APPLYTYPE field.`type`)
    def validate =
      REF("json") DOT "\\" APPLY (LIT(field.rawName)) DOT "validate" APPLYTYPE field.`type` APPLY (reads)
    def validateOpt =
      REF("json") DOT "\\" APPLY (LIT(field.rawName)) DOT "validateOpt" APPLYTYPE field.`type` APPLY (reads)
    def validateOptWithDefault(defaultValue: Tree) =
      REF("json") DOT "\\" APPLY (LIT(field.rawName)) DOT "validateOpt" APPLYTYPE field.`type` APPLY (reads) DOT "map" APPLY (REF(
        "_"
      ) DOT "getOrElse" APPLY defaultValue)

    if (field.required && !field.forceOptional) {
      validate
    } else if (field.repeated && !field.forceOptional) {
      validateOptWithDefault(field.`type` APPLY ())
    } else {
      if (field.defaultValue.isDefined && !field.forceOptional) {
        validateOptWithDefault(field.defaultValue.get)
      } else {
        validateOpt
      }
    }
  }

  def validateConstraints(c: Seq[ConstraintT[_]])(exp: Tree): Tree = {
    if (c.isEmpty) {
      exp
    } else {
      @tailrec
      def buildChain(constraints: List[ConstraintT[_]], chain: Tree): Tree =
        constraints match {
          case Nil => chain
          case c :: rs => buildChain(rs, chain INFIX ("keepAnd", validateConstraint(c)))
        }

      exp APPLY buildChain(c.tail.to[List], validateConstraint(c.head))
    }
  }

  /** @return a code gen expression that represents a playJS reads validation */
  def validateConstraint(c: ConstraintT[_]): Tree =
    c match {
      case max @ ConstraintT.MaxLength(len) =>
        REF(max.name) APPLYTYPE StringClass APPLY (constraintToValue(max, len))
      case min @ ConstraintT.MinLength(len) =>
        REF(min.name) APPLYTYPE StringClass APPLY (constraintToValue(min, len))
      case p @ ConstraintT.Pattern(pattern) =>
        REF(p.name) APPLY (constraintToValue(p, pattern))
      case max @ ConstraintT.MaxItems(len, t) =>
        REF(max.name) APPLYTYPE t APPLY (constraintToValue(max, len))
      case min @ ConstraintT.MinItems(len, t) =>
        REF(min.name) APPLYTYPE t APPLY (constraintToValue(min, len))
      case max @ ConstraintT.Max(v, t) =>
        REF(max.name) APPLYTYPE t APPLY (constraintToValue(max, v))
      case min @ ConstraintT.Min(v, t) =>
        REF(min.name) APPLYTYPE t APPLY (constraintToValue(min, v))
      case keyPattern @ ConstraintT.KeyPattern(pattern, mapValType) =>
        REF(keyPattern.name) APPLYTYPE mapValType APPLY (constraintToValue(keyPattern, pattern))
    }

  def constraintToValue[C](c: ConstraintT[C], v: C): Tree =
    c match {
      case ConstraintT.Pattern(pattern) => LIT(pattern) DOT "r"
      case ConstraintT.KeyPattern(pattern, _) => LIT(pattern) DOT "r"
      case _ => LIT(v) // decent assumption for built-ins, probably not much else
    }

  /** a code gen expression for a field that represents the constraint limit */
  def limitField[C](constraint: ConstraintT[C], field: FieldT): Option[Tree] = {
    val fieldName = scalaFieldName(underscoreToCamel(camelify(s"constraint_${field.rawName}_${constraint.name}".replace("-", "_"))))
    val limit = (VAL(fieldName) := constraintToValue[C](constraint, constraint.constraint))
    Option(limit)
  }
}
