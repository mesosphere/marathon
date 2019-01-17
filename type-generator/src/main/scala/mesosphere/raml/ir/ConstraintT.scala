package mesosphere.raml.ir

import mesosphere.raml.backend.{PlayReads, camelify, scalaFieldName, underscoreToCamel}
import treehugger.forest._
import definitions._
import treehugger.forest
import treehuggerDSL._

import scala.annotation.tailrec

sealed trait ConstraintT[C] { self =>
  val name: String
  val constraint: C
  val constraintToValue: C => Tree = { (c: C) => LIT(c) } // decent assumption for built-ins, probably not much else

  /** false indicates custom, non-play constraint implementations that we've implemented as part of this generator */
  val builtIn: Boolean

  /** @return a code gen expression that represents a playJS reads validation */
  def validate(): Tree

  /** a code gen expression for a field that represents the constraint limit */
  val limitField: Option[Tree] = None

  /** decorate this constraint with a `limitField` implementation */
  def copyWith(lf: Option[Tree] = None): ConstraintT[C]

  def withFieldLimit(f: FieldT): ConstraintT[C] = {
    val fieldName = scalaFieldName(underscoreToCamel(camelify(s"constraint_${f.rawName}_${name}".replace("-", "_"))))
    val limit = (VAL(fieldName) := constraintToValue(constraint))
    copyWith(Option(limit))
  }
}

object ConstraintT {

  // built-in playJS validators

  case class MaxLength(len: Integer, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Integer] {
    val name = "maxLength"
    val constraint = len
    val validateFunc = { REF(_) APPLYTYPE StringClass APPLY (_) }
    val builtIn = true

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Integer] = copy(limitField = lf)
  }

  case class MinLength(len: Integer, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Integer] {
    val name = "minLength"
    val constraint = len
    override val validateFunc: (String, forest.Tree) => forest.Tree =  { REF(_) APPLYTYPE StringClass APPLY (_) }
    val builtIn = true

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Integer] = copy(limitField = lf)
  }

  case class Pattern(p: String, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[String] {
    val name = "pattern"
    val constraint = p
    val builtIn = true
    override val constraintToValue = { (c: String) => LIT(c) DOT "r" }
    override val validateFunc = { REF(_) APPLY (_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[String] = copy(limitField = lf)
  }

  case class MaxItems(len: Integer, t: Type, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Integer] {
    val name = "maxLength"
    val constraint = len
    val builtIn = true
    val validateFunc = { REF(_) APPLYTYPE t APPLY(_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Integer] = copy(limitField = lf)
  }

  case class MinItems(len: Integer, t: Type, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Integer] {
    val name = "minLength"
    val constraint = len
    val builtIn = true
    val validateFunc = { REF(_) APPLYTYPE t APPLY(_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Integer] = copy(limitField = lf)
  }

  case class Max(v: Number, t: Type, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Number] {
    val name = "max"
    val constraint = v
    val builtIn = true
    val validateFunc = { REF(_) APPLYTYPE t APPLY(_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Number] = copy(limitField = lf)
  }

  case class Min(v: Number, t: Type, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[Number] {
    val name = "min"
    val constraint = v
    val builtIn = true
    val validateFunc = { REF(_) APPLYTYPE t APPLY(_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[Number] = copy(limitField = lf)
  }

  // custom validator implementations follow

  case class KeyPattern(p: String, mapValType: Type, override val limitField: Option[forest.Tree] = None) extends BasicConstraint[String] {
    val name = "keyPattern"
    val constraint = p
    val builtIn = false
    override val constraintToValue = { (c: String) => LIT(c) DOT "r" }
    val validateFunc = { REF(_) APPLYTYPE mapValType APPLY (_) }

    override def copyWith(lf: Option[Tree] = limitField): ConstraintT[String] = copy(limitField = lf)
  }

  trait BasicConstraint[C] extends ConstraintT[C] {
    override val name: String
    override val constraint: C
    val validateFunc: (String, Tree) => Tree
    override val builtIn: Boolean
    override val limitField: Option[Tree] = None

    override def validate(): Tree = validateFunc(name, constraintToValue(constraint))
  }

  implicit class Constraints(val c: Seq[ConstraintT[_]]) extends AnyVal {
    def validate(exp: Tree): Tree = {
      if (c.isEmpty) {
        exp
      } else {
        @tailrec
        def buildChain(constraints: List[ConstraintT[_]], chain: Tree): Tree = constraints match {
          case Nil => chain
          case c :: rs => buildChain(rs, chain INFIX("keepAnd", c.validate()))
        }

        exp APPLY buildChain(c.tail.to[List], c.head.validate())
      }
    }

  }

  implicit class AllConstraints(val c: Seq[Seq[ConstraintT[_]]]) extends AnyVal {
    def requiredImports: Seq[Tree] = {
      val flattened = c.flatten
      if (flattened.isEmpty) {
        Nil
      } else {
        Seq(
          Option(IMPORT(PlayReads DOT "_")),
          if (c.exists(_.size > 1)) Option(IMPORT("play.api.libs.functional.syntax._")) else None,
          flattened.find(!_.builtIn).map(_ => IMPORT("RamlConstraints._"))
        )
      }.flatten
    }
  }
}
