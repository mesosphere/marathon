package mesosphere.raml.ir

import mesosphere.raml.backend.{PlayReads, camelify, scalaFieldName, underscoreToCamel}
import treehugger.forest._
import definitions._
import treehugger.forest
import treehuggerDSL._

sealed trait ConstraintT[C] { self =>
  val name: String
  val constraint: C

  /** false indicates custom, non-play constraint implementations that we've implemented as part of this generator */
  val builtIn: Boolean
}

object ConstraintT {

  case class MaxLength(constraint: Integer) extends ConstraintT[Integer] {
    val name: String = "maxLength"
    val builtIn = true
  }

  case class MinLength(constraint: Integer) extends ConstraintT[Integer] {
    val name = "minLength"
    val builtIn = true
  }

  case class Pattern(p: String) extends ConstraintT[String] {
    val name = "pattern"
    val constraint = p
    val builtIn = true
  }

  case class MaxItems(constraint: Integer, t: Type) extends ConstraintT[Integer] {
    val name = "maxLength"
    val builtIn = true
  }

  case class MinItems(constraint: Integer, t: Type) extends ConstraintT[Integer] {
    val name = "minLength"
    val builtIn = true
  }

  case class Max(constraint: Number, t: Type) extends ConstraintT[Number] {
    val name = "max"
    val builtIn = true
  }

  case class Min(constraint: Number, t: Type) extends ConstraintT[Number] {
    val name = "min"
    val builtIn = true
  }

  // custom validator implementations follow

  case class KeyPattern(constraint: String, mapValType: Type) extends ConstraintT[String] {
    val name = "keyPattern"
    val builtIn = false
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
