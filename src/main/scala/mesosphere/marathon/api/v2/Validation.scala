package mesosphere.marathon.api.v2

import java.net._

import com.wix.accord._
import mesosphere.marathon.{ AllConf, ValidationFailedException }
import mesosphere.marathon.state.FetchUri
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.collection.GenTraversableOnce
import scala.reflect.ClassTag
import scala.util.Try

object Validation {
  def validateOrThrow[T](t: T)(implicit validator: Validator[T]): T = validate(t) match {
    case Success    => t
    case f: Failure => throw new ValidationFailedException(t, f)
  }

  implicit def optional[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(Success)
    }
  }

  def definedAnd[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(
        Failure(Set(RuleViolation(None, "not defined", None)))
      )
    }
  }

  def conditional[T](b: T => Boolean)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = if (!b(t)) Success else validator(t)
  }

  implicit def every[T](implicit validator: Validator[T]): Validator[Iterable[T]] = {
    new Validator[Iterable[T]] {
      override def apply(seq: Iterable[T]): Result = {

        val violations = seq.map(item => (item, validator(item))).zipWithIndex.collect {
          case ((item, f: Failure), pos: Int) => GroupViolation(item, "not valid", Some(s"($pos)"), f.violations)
        }

        if (violations.isEmpty) Success
        else Failure(Set(GroupViolation(seq, "Seq contains elements, which are not valid.", None, violations.toSet)))
      }
    }
  }

  def mapDescription[T](change: String => String)(wrapped: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(value: T): Result = {
      wrapped(value) match {
        case Success => Success
        case f: Failure =>
          Failure(
            f.violations.map(v => v.description.fold(v)(oldDescription => v.withDescription(change(oldDescription))))
          )
      }
    }
  }

  def featureEnabled[T](feature: String): Validator[T] = {
    isTrue(s"Feature $feature is not enabled. Enable with --enable_features $feature)") { _ =>
      AllConf.isFeatureSet(feature)
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

  def allRuleViolationsWithFullDescription(violation: Violation,
                                           parentDesc: Option[String] = None,
                                           prependSlash: Boolean = false): Set[RuleViolation] = {
    def concatPath(parent: String, child: Option[String], slash: Boolean): String = {
      child.map(c => parent + { if (slash) "/" else "" } + c).getOrElse(parent)
    }

    violation match {
      case r: RuleViolation => Set(
        parentDesc.map {
          p =>
            r.description.map {
              // Error is on object level, having a parent description. Omit 'value', prepend '/' as root.
              case "value" => r.withDescription("/" + p)
              // Error is on property level, having a parent description. Prepend '/' as root.
              case s: String =>
                // This was necessary if you validate a sub field, e.g. volume.external.size is valid(...)
                // generates a description containing "external.size".
                val slashPath: Some[String] = Some(s.replace('.', '/'))
                r.withDescription(concatPath("/" + p, slashPath, prependSlash))
              // Error is on unknown level, having a parent description. Prepend '/' as root.
            } getOrElse r.withDescription("/" + p)
        } getOrElse {
          r.withDescription(r.description.map {
            // Error is on object level, having no parent description, being a root error.
            case "value"   => "/"
            // Error is on property level, having no parent description, being a property of root error.
            case s: String => "/" + s
          } getOrElse "/")
        })
      case g: GroupViolation => g.children.flatMap { c =>
        val dot = g.value match {
          case _: Iterable[_] => false
          case _              => true
        }

        val desc = parentDesc.map {
          p => Some(concatPath(p, g.description, prependSlash))
        } getOrElse {
          g.description.map(d => concatPath("", Some(d), prependSlash))
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
              else Failure(Set(RuleViolation(url, "URL could not be resolved.", None)))
            case other: URLConnection =>
              other.getInputStream
              Success //if we come here, we could read the stream
          }
        }.getOrElse(
          Failure(Set(RuleViolation(url, "URL could not be resolved.", None)))
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

  def elementsAreUnique[A](errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq, errorMessage)
    }
  }

  def elementsAreUniqueBy[A, B](fn: A => B,
                                errorMessage: String = "Elements must be unique.",
                                filter: B => Boolean = { _: B => true }): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq.map(fn).filter(filter), errorMessage)
    }
  }

  def elementsAreUniqueByOptional[A, B](fn: A => GenTraversableOnce[B],
                                        errorMessage: String = "Elements must be unique.",
                                        filter: B => Boolean = { _: B => true }): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq.flatMap(fn).filter(filter), errorMessage)
    }
  }

  def elementsAreUniqueWithFilter[A](fn: A => Boolean,
                                     errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq.filter(fn), errorMessage)
    }
  }

  private[this] def areUnique[A](seq: Seq[A], errorMessage: String): Result = {
    if (seq.size == seq.distinct.size) Success
    else Failure(Set(RuleViolation(seq, errorMessage, None)))
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

  def notOneOf[T <: AnyRef](options: T*): Validator[T] = {
    import ViolationBuilder._
    new NullSafeValidator[T](
      test = !options.contains(_),
      failure = _ -> s"can not be one of (${options.mkString(",")})"
    )
  }

  def oneOf[T <: AnyRef](options: Set[T]): Validator[T] = {
    import ViolationBuilder._
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

  def oneOf[T <: AnyRef](options: T*): Validator[T] = {
    import ViolationBuilder._
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

  def isTrue[T](constraint: String)(test: T => Boolean): Validator[T] = isTrue[T]((_: T) => constraint)(test)

  def isTrue[T](constraint: T => String)(test: T => Boolean): Validator[T] = new Validator[T] {
    import ViolationBuilder._
    override def apply(value: T): Result = {
      if (test(value)) Success else RuleViolation(value, constraint(value), None)
    }
  }

  def group(violations: Iterable[Violation]): Result = if (violations.nonEmpty) Failure(violations.to[Set]) else Success

  /**
    * For debugging purposes only.
    * Since the macro removes all logging statements in the validator itself.
    * Usage: info("message") { yourValidator }
    */
  def info[T](message: String)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = {
      LoggerFactory.getLogger(Validation.getClass).info(s"Validate: $message on $t")
      validator(t)
    }
  }
}
