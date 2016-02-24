package mesosphere.marathon.api.v2

import java.net._

import com.wix.accord._
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.state.FetchUri
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json._

import scala.reflect.ClassTag
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
    Json.obj(
      "message" -> "Object is not valid",
      "details" -> {
        f.violations
          .flatMap(allRuleViolationsWithFullDescription(_))
          .groupBy(_.description)
          .map {
            case (description, ruleViolation) =>
              Json.obj(
                "path" -> description,
                "errors" -> ruleViolation.map(r => JsString(r.constraint))
              )
          }
      })
  }

  private def allRuleViolationsWithFullDescription(violation: Violation,
                                                   parentDesc: Option[String] = None,
                                                   prependDot: Boolean = false): Set[RuleViolation] = {
    def concatPath(parent: String, child: Option[String], dot: Boolean): String = {
      child.map(c => parent + { if (dot) "." else "" } + c).getOrElse(parent)
    }

    violation match {
      case r: RuleViolation => Set(
        parentDesc.map {
          p =>
            r.description.map {
              case "value"   => r.withDescription(p)
              case s: String => r.withDescription(concatPath(p, r.description, prependDot))
            } getOrElse r.withDescription(p)
        } getOrElse {
          r.withDescription(r.description.map {
            case "value"   => "self"
            case s: String => s
          } getOrElse "self")
        })
      case g: GroupViolation => g.children.flatMap { c =>
        val dot = g.value match {
          case _: Iterable[_] => false
          case _              => true
        }

        val desc = parentDesc.map {
          p => Some(concatPath(p, g.description, prependDot))
        } getOrElse {
          g.description.map(d => concatPath("", Some(d), prependDot))
        }
        allRuleViolationsWithFullDescription(c, desc, dot)
      }
    }
  }

  def urlCanBeResolvedValidator: Validator[String] = {
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

  def fetchUriIsValid: Validator[FetchUri] = {
    new Validator[FetchUri] {
      def apply(uri: FetchUri) = {
        try {
          new URI(uri.uri)
          Success
        }
        catch {
          case _: URISyntaxException => Failure(Set(RuleViolation(uri.uri, "URI has invalid syntax.", None)))
        }
      }
    }
  }

  def elementsAreUnique[A](p: Seq[A] => Seq[A] = { seq: Seq[A] => seq }): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = {
        val filteredSeq = p(seq)
        if (filteredSeq.size == filteredSeq.distinct.size) Success
        else Failure(Set(RuleViolation(seq, "Elements must be unique", None)))
      }
    }
  }

  def theOnlyDefinedOptionIn[A <: Product: ClassTag, B](product: A): Validator[Option[B]] =
    new Validator[Option[B]] {
      def apply(option: Option[B]) = {
        option match {
          case Some(prop) =>
            val n = product.productIterator.count {
              case Some(_) => true
              case _       => false
            }

            if (n == 1)
              Success
            else
              Failure(Set(RuleViolation(product, s"not allowed in conjunction with other properties.", None)))
          case None => Success
        }
      }
    }

  def oneOf[T <: AnyRef](options: T*): Validator[T] = {
    import ViolationBuilder._
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

}
