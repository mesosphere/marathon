package mesosphere

import com.wix.accord.{ Failure, Result, Success, Validator }
import mesosphere.marathon.Normalization
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.api.v2.Validation.ConstraintViolation
import org.scalatest._
import org.scalatest.matchers.{ BePropertyMatchResult, BePropertyMatcher, MatchResult, Matcher }
import play.api.libs.json.{ Json, JsError, Format }
import mesosphere.marathon.api.akkahttp.EntityMarshallers

/**
  * Provides a set of scalatest matchers for use when testing validation.
  *
  * Wix Accord does provide matchers in import com.wix.accord.scalatest.ResultMatchers; however, the interface and the
  * output of these matchers is not as friendly as we would prefer.
  */
trait ValidationTestLike extends Validation {
  this: Assertions =>

  /**
    * Validator which takes an object, serializes it to JSON, and then parses it back, allowing it to test validations
    * specified in our RAML layer
    */
  def roundTripValidator[T](underlyingValidator: Option[Validator[T]])(implicit format: Format[T]) = new Validator[T] {
    override def apply(obj: T) = {
      Json.fromJson[T](Json.toJson(obj)) match {
        case err: JsError =>
          EntityMarshallers.jsErrorToFailure(err)
        case obj => underlyingValidator.map { _(obj.get) } getOrElse Success
      }
    }
  }

  protected implicit val normalizeResult: Normalization[Result] = Normalization {
    // normalize failures => human readable error messages
    case f: Failure => f
    case x => x
  }

  def withValidationClue[T](f: => T): T = scala.util.Try { f }.recover {
    // handle RAML validation errors
    case vfe: ValidationFailedException => fail(vfe.failure.violations.toString())
    case th => throw th
  }.get

  private def describeViolation(c: ConstraintViolation) =
    s"""- "${c.path}" -> "${c.constraint}""""

  case class haveViolations(expectedViolations: (String, String)*) extends Matcher[Result] {
    val expectedConstraintViolations = expectedViolations.map(ConstraintViolation.tupled)
    override def apply(result: Result): MatchResult = {
      result match {
        case Success =>
          MatchResult(
            matches = false,
            "Validation succeeded, had no violations",
            "" /* This MatchResult is explicitly false; negated failure does not apply */ )
        case f: Failure =>
          val violations = Validation.allViolations(f)
          val matches = expectedConstraintViolations.forall { e => violations contains e }
          MatchResult(
            matches,
            s"""Validation failed, but expected violation not in actual violation set
               |  Expected:
               |  ${expectedConstraintViolations.map(describeViolation).mkString("\n  ")}
               |  All violations:
               |  ${violations.map(describeViolation).mkString("\n  ")}
               |""".stripMargin.trim,
            s"""Validation failed, but expected violations were in actual violation set
               |  Expected:
               |  ${expectedConstraintViolations.map(describeViolation).mkString("\n  ")}
               |  All violations:
               |  ${violations.map(describeViolation).mkString("\n  ")}
               |""".stripMargin.trim)
      }
    }
  }

  object aSuccess extends BePropertyMatcher[Result] {
    override def apply(result: Result): BePropertyMatchResult = {
      result match {
        case Success =>
          BePropertyMatchResult(true, "Expected a failure, got success")
        case f: Failure =>
          val violations = Validation.allViolations(f)
          BePropertyMatchResult(
            false,
            s"""Validation failed, but expected success
               |  All violations:
               |  ${violations.map(describeViolation).mkString("\n  ")}
               |""".stripMargin.trim)
      }
    }
  }
}
