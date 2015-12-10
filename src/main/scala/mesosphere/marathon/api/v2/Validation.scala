package mesosphere.marathon.api.v2

import java.net.{ URLConnection, HttpURLConnection, URL }

import com.wix.accord._
import mesosphere.marathon.ValidationFailedException

import play.api.libs.json._

import scala.util.Try

object Validation {

  def validate[T](t: T)(implicit validator: Validator[T]): Result = validator.apply(t)
  def validateOrThrow[T](t: T)(implicit validator: Validator[T]): T = validate(t) match {
    case Success    => t
    case f: Failure => throw new ValidationFailedException(t, f)
  }

  implicit def optional[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(Success)
    }
  }

  implicit def every[T](implicit validator: Validator[T]): Validator[Iterable[T]] = {
    new Validator[Iterable[T]] {
      override def apply(seq: Iterable[T]): Result = {

        val violations = seq.map(item => (item, validator(item))).zipWithIndex.collect {
          case ((item, f: Failure), pos: Int) => GroupViolation(item, "not valid", Some(s"[$pos]"), f.violations)
        }

        if (violations.isEmpty) Success
        else Failure(Set(GroupViolation(seq, "seq contains elements, which are not valid", None, violations.toSet)))
      }
    }
  }

  implicit lazy val failureWrites: Writes[Failure] = Writes { f =>
    // TODO AW: get rid of toSeq
    Json.obj("errors" -> {
      f.violations.size match {
        case 1 => violationToJsValue(f.violations.head)
        case _ => JsArray(f.violations.toSeq.map(violationToJsValue(_)))
      }
    })
  }

  implicit lazy val ruleViolationWrites: Writes[RuleViolation] = Writes { v =>
    Json.obj(
      "attribute" -> v.description,
      "error" -> v.constraint
    )
  }

  implicit lazy val groupViolationWrites: Writes[GroupViolation] = Writes { v =>
    // TODO AW: get rid of toSeq
    v.value match {
      case Some(s) =>
        violationToJsValue(v.children.head, v.description)
      case _ => v.children.size match {
        case 1 => violationToJsValue(v.children.head, v.description)
        case _ => JsArray(v.children.toSeq.map(c =>
          violationToJsValue(c, v.description, parentSeq = true)
        ))
      }
    }
  }

  private def concatPath(parent: String, child: Option[String], parentSeq: Boolean): String = {
    // TODO AW: fix not point in array issue
    child.map(c => parent + { if (parentSeq) "." else "." } + c).getOrElse(parent)
  }

  private def violationToJsValue(violation: Violation,
                                 parentDesc: Option[String] = None,
                                 parentSeq: Boolean = false): JsValue = {
    violation match {
      case r: RuleViolation => Json.toJson(parentDesc.map(p =>
        r.withDescription(concatPath(p, r.description, parentSeq)))
        .getOrElse(r))
      case g: GroupViolation => Json.toJson(parentDesc.map(p =>
        g.withDescription(concatPath(p, g.description, parentSeq)))
        .getOrElse(g))
    }
  }

  def urlsCanBeResolvedValidator: Validator[String] = {
    new Validator[String] {
      def apply(url: String) = {
        Try {
          new URL(url).openConnection() match {
            case http: HttpURLConnection =>
              http.setRequestMethod("HEAD")
              if (http.getResponseCode == HttpURLConnection.HTTP_OK) Success
              else Failure(Set(RuleViolation(url, "url could not be resolved", None)))
            case other: URLConnection =>
              other.getInputStream
              Success //if we come here, we could read the stream
          }
        }.getOrElse(
          Failure(Set(RuleViolation(url, "url could not be resolved", None)))
        )
      }
    }
  }

  def getAllRuleConstrains(r: Result): Seq[String] = {
    def loop(v: Violation): Seq[String] = {
      v match {
        case g: GroupViolation => g.children.flatMap(c => loop(c)).toSeq
        case r: RuleViolation => Seq(r.constraint)
      }
    }

    r match {
      case f: Failure => f.violations.flatMap(loop).toSeq
      case _ => Seq.empty[String]
    }
  }
}
