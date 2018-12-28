package mesosphere.raml.ir

import mesosphere.raml.backend._

import treehugger.forest._
import definitions._
import treehuggerDSL._

import scala.annotation.tailrec

sealed trait Constraint[C] { self =>
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
  def copyWith(lf: Option[Tree] = None): Constraint[C]

  def withFieldLimit(f: FieldT): Constraint[C] = {
    val fieldName = scalaFieldName(underscoreToCamel(camelify(s"constraint_${f.rawName}_${name}".replace("-", "_"))))
    val limit = (VAL(fieldName) := constraintToValue(constraint))
    copyWith(Option(limit))
  }
}

object Constraint {

  // built-in playJS validators

  def MaxLength(len: Integer) = Constraint("maxLength", len, builtIn = true) { REF(_) APPLYTYPE StringClass APPLY(_) }
  def MinLength(len: Integer) = Constraint("minLength", len, builtIn = true) { REF(_) APPLYTYPE StringClass APPLY(_) }

  def Pattern(p: String) = Constraint("pattern", p, builtIn = true, (c: String) => LIT(c) DOT "r") { REF(_) APPLY(_) }

  def MaxItems(len: Integer, t: Type) = Constraint("maxLength", len, builtIn = true) { REF(_) APPLYTYPE t APPLY(_) }
  def MinItems(len: Integer, t: Type) = Constraint("minLength", len, builtIn = true) { REF(_) APPLYTYPE t APPLY(_) }

  def Max(v: Number, t: Type) = Constraint("max", v, builtIn = true) { REF(_) APPLYTYPE t APPLY(_) }
  def Min(v: Number, t: Type) = Constraint("min", v, builtIn = true) { REF(_) APPLYTYPE t APPLY(_) }

  // custom validator implementations follow

  def KeyPattern(p: String, mapValType: Type) =
    Constraint("keyPattern", p, builtIn = false, (c: String) => LIT(c) DOT "r") { REF(_) APPLYTYPE mapValType APPLY(_) }

  case class BasicConstraint[C](
                                 override val name: String,
                                 override val constraint: C,
                                 override val constraintToValue: C => Tree,
                                 val validateFunc: (String, Tree) => Tree,
                                 override val builtIn: Boolean,
                                 override val limitField: Option[Tree] = None) extends Constraint[C] {
    override def validate(): Tree = validateFunc(name, constraintToValue(constraint))
    override def copyWith(lf: Option[Tree] = limitField): Constraint[C] = copy(limitField = lf)
  }

  def apply[C](n: String, c: C, builtIn: Boolean, c2v: C => Tree = { (c: C) => LIT(c) })(f: (String, Tree) => Tree): Constraint[C] =
    new BasicConstraint(n, c, c2v, f, builtIn)

  implicit class Constraints(val c: Seq[Constraint[_]]) extends AnyVal {
    def validate(exp: Tree): Tree = {
      if (c.isEmpty) {
        exp
      } else {
        @tailrec
        def buildChain(constraints: List[Constraint[_]], chain: Tree): Tree = constraints match {
          case Nil => chain
          case c :: rs => buildChain(rs, chain INFIX("keepAnd", c.validate()))
        }

        exp APPLY buildChain(c.tail.to[List], c.head.validate())
      }
    }

  }

  implicit class AllConstraints(val c: Seq[Seq[Constraint[_]]]) extends AnyVal {
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
