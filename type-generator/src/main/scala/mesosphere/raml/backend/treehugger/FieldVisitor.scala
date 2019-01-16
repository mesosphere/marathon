package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.{PlayPath, PlayReads, camelify, scalaFieldName, underscoreToCamel}
import mesosphere.raml.ir.{ConstraintT, FieldT}
import treehugger.forest._
import definitions._
import mesosphere.raml.ir.ConstraintT.BasicConstraint
import treehuggerDSL._

import scala.annotation.tailrec

object FieldVisitor {

  def playValidator(field: FieldT) =  {
    def reads = validateConstraints(field.constraints)(PlayPath DOT "read" APPLYTYPE field.`type`)
    def validate =
      REF("json") DOT "\\" APPLY(LIT(field.rawName)) DOT "validate" APPLYTYPE field.`type` APPLY(reads)
    def validateOpt =
      REF("json") DOT "\\" APPLY(LIT(field.rawName)) DOT "validateOpt" APPLYTYPE field.`type` APPLY(reads)
    def validateOptWithDefault(defaultValue: Tree) =
      REF("json") DOT "\\" APPLY(LIT(field.rawName)) DOT "validateOpt" APPLYTYPE field.`type` APPLY(reads) DOT "map" APPLY (REF("_") DOT "getOrElse" APPLY defaultValue)

    if (field.required && !field.forceOptional) {
      validate
    } else if (field.repeated && !field.forceOptional) {
      validateOptWithDefault(field.`type` APPLY())
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
      def buildChain(constraints: List[ConstraintT[_]], chain: Tree): Tree = constraints match {
        case Nil => chain
        case c :: rs => buildChain(rs, chain INFIX("keepAnd", validateConstraint(c)))
      }

      exp APPLY buildChain(c.tail.to[List], validateConstraint(c.head))
    }
  }

  /** @return a code gen expression that represents a playJS reads validation */
  def validateConstraint(c: ConstraintT[_]): Tree = c match {
    case max @ ConstraintT.MaxLength(name, len, _) => REF(name) APPLYTYPE StringClass APPLY (max.constraintToValue(len))
    case min @ ConstraintT.MinLength(len, _) => REF(min.name) APPLYTYPE StringClass APPLY (min.constraintToValue(len))
    case p @ ConstraintT.Pattern(pattern, _) =>  REF(p.name) APPLY (p.constraintToValue(pattern))
    case max @ ConstraintT.MaxItems(len, t, _) => REF(max.name) APPLYTYPE t APPLY(max.constraintToValue(len))
    case min @ ConstraintT.MinItems(len, t, _) => REF(min.name) APPLYTYPE t APPLY(min.constraintToValue(len))
    case max @ ConstraintT.Max(v, t, _) => REF(max.name) APPLYTYPE t APPLY(max.constraintToValue(v))
    case min @ ConstraintT.Min(v, t, _) => REF(min.name) APPLYTYPE t APPLY(min.constraintToValue(v))
    case keyPattern @ ConstraintT.KeyPattern(pattern, mapValType, _) =>  REF(keyPattern.name) APPLYTYPE mapValType APPLY (keyPattern.constraintToValue(pattern))
  }

  def limitField(constraint: ConstraintT[_], field: FieldT): Option[Tree] = {
    constraint.withFieldLimit(field).limitField
    val fieldName = scalaFieldName(underscoreToCamel(camelify(s"constraint_${f.rawName}_${name}".replace("-", "_"))))
    val limit = (VAL(fieldName) := constraint.constraintToValue(constraint))
    constraint.copyWith(Option(limit))
  }
}
