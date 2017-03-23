package mesosphere.marathon
package api.v2

import java.net._

import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.state.FetchUri
import mesosphere.marathon.stream.Implicits._
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.collection.GenTraversableOnce
import scala.util.matching.Regex

import scala.language.implicitConversions

// TODO(jdef) move this into package "validation"
trait Validation {
  def validateOrThrow[T](t: T)(implicit validator: Validator[T]): T = validate(t) match {
    case Success => t
    case f: Failure => throw ValidationFailedException(t, f)
  }

  implicit def optional[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(Success)
    }
  }

  /**
    * when used in a `validator` should be wrapped with `valid(...)`
    */
  def definedAnd[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(
        Failure(Set(RuleViolation(None, "not defined", None)))
      )
    }
  }

  /**
    * When the supplied expression `b` yields `true` then the supplied (implicit) validator is returned; otherwise
    * `Success`. Similar to [[conditional]] except the predicate producing the boolean is not parameterized.
    */
  def implied[T](b: => Boolean)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = if (!b) Success else validator(t)
  }

  def conditional[T](b: T => Boolean)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = if (!b(t)) Success else validator(t)
  }

  implicit def every[T](implicit validator: Validator[T]): Validator[Iterable[T]] = {
    new Validator[Iterable[T]] {
      override def apply(seq: Iterable[T]): Result = {

        val violations: Set[Violation] = seq match {
          case m: Map[_, _] =>
            m.map(item => (item, validator(item))).collect {
              case ((k, v), f: Failure) => GroupViolation(k, "not valid", Some(s"($k)"), f.violations)
            }(collection.breakOut)
          case _ =>
            seq.map(item => (item, validator(item))).zipWithIndex.collect {
              case ((item, f: Failure), pos: Int) => GroupViolation(item, "not valid", Some(s"($pos)"), f.violations)
            }(collection.breakOut)
        }

        if (violations.isEmpty) Success
        else Failure(Set(GroupViolation(seq, "contains elements, which are not valid.", None, violations)))
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

  def featureEnabled[T](enabledFeatures: Set[String], feature: String): Validator[T] = {
    isTrue(s"Feature $feature is not enabled. Enable with --enable_features $feature)") { _ =>
      enabledFeatures.contains(feature)
    }
  }

  /**
    * Yield a validator for `T` only if the supplied `feature` is present in the set of `enabledFeatures`; otherwise
    * `Success`.
    */
  def featureEnabledImplies[T](enabledFeatures: Set[String], feature: String)(implicit v: Validator[T]): Validator[T] =
    implied[T](enabledFeatures.contains(feature))(v)

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

  // TODO: fix non-tail recursion
  def allRuleViolationsWithFullDescription(
    violation: Violation,
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
            case "value" => "/"
            // Error is on property level, having no parent description, being a property of root error.
            case s: String => "/" + s
          } getOrElse "/")
        })
      case g: GroupViolation => g.children.flatMap { c =>
        val dot = g.value match {
          case _: Iterable[_] => false
          case _ => true
        }

        val desc: Option[String] = parentDesc.map { p =>
          concatPath(p, g.description, prependSlash)
        }.orElse(g.description.map(d => concatPath("", Some(d), prependSlash)))
        allRuleViolationsWithFullDescription(c, desc, dot)
      }
    }
  }

  def urlIsValid: Validator[String] = {
    new Validator[String] {
      def apply(url: String) = {
        try {
          new URL(url)
          Success
        } catch {
          case e: MalformedURLException => Failure(Set(RuleViolation(url, e.getMessage, None)))
        }
      }
    }
  }

  def uriIsValid: Validator[String] = {
    new Validator[String] {
      def apply(uri: String) = {
        try {
          new URI(uri)
          Success
        } catch {
          case _: URISyntaxException => Failure(Set(RuleViolation(uri, "URI has invalid syntax.", None)))
        }
      }
    }
  }

  def fetchUriIsValid: Validator[FetchUri] = validator[FetchUri] { fetch =>
    fetch.uri is valid(uriIsValid)
  }

  def elementsAreUnique[A](errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq, errorMessage)
    }
  }

  def elementsAreUniqueBy[A, B](
    fn: A => B,
    errorMessage: String = "Elements must be unique.",
    filter: B => Boolean = { _: B => true }): Validator[Iterable[A]] = {
    new Validator[Iterable[A]] {
      def apply(seq: Iterable[A]) = areUnique(seq.map(fn).filterAs(filter)(collection.breakOut), errorMessage)
    }
  }

  def elementsAreUniqueByOptional[A, B](
    fn: A => GenTraversableOnce[B],
    errorMessage: String = "Elements must be unique.",
    filter: B => Boolean = { _: B => true }): Validator[Iterable[A]] = {
    new Validator[Iterable[A]] {
      def apply(seq: Iterable[A]) = areUnique(seq.flatMap(fn).filterAs(filter)(collection.breakOut), errorMessage)
    }
  }

  def elementsAreUniqueWithFilter[A](
    fn: A => Boolean,
    errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq.filter(fn), errorMessage)
    }
  }

  private[this] def areUnique[A](seq: Seq[A], errorMessage: String): Result = {
    if (seq.size == seq.distinct.size) Success
    else Failure(Set(RuleViolation(seq, errorMessage, None)))
  }

  def theOnlyDefinedOptionIn[A <: Product, B](product: A): Validator[Option[B]] =
    new Validator[Option[B]] {
      def apply(option: Option[B]) = {
        option match {
          case Some(prop) =>
            val n = product.productIterator.count {
              case Some(_) => true
              case _ => false
            }

            if (n == 1)
              Success
            else
              Failure(Set(RuleViolation(product, "not allowed in conjunction with other properties.", None)))
          case None => Success
        }
      }
    }

  def notOneOf[T <: AnyRef](options: T*): Validator[T] = {
    new NullSafeValidator[T](
      test = !options.contains(_),
      failure = _ -> s"can not be one of (${options.mkString(",")})"
    )
  }

  def oneOf[T <: AnyRef](options: Set[T]): Validator[T] = {
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

  @SuppressWarnings(Array("UnsafeContains"))
  def oneOf[T <: AnyRef](options: T*): Validator[T] = {
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

  def matchRegexWithFailureMessage(regex: Regex, failureMessage: String): Validator[String] =
    new NullSafeValidator[String](
      test = _.matches(regex.regex),
      failure = _ -> failureMessage
    )

  def validateAll[T](x: T, all: Validator[T]*): Result = all.map(v => validate(x)(v)).fold(Success)(_ and _)
}

object Validation extends Validation {

  def forAll[T](all: Validator[T]*): Validator[T] = new Validator[T] {
    override def apply(x: T): Result = validateAll(x, all: _*)
  }

  /**
    * Improve legibility of long validation rule sets by removing some of the "wordiness" of using `conditional`
    * (typically followed by `isTrue` (which then tends to be wordy) or some other wordy thing).
    */
  implicit def conditionalTuple[T](t: (T => Boolean, Validator[T])): Validator[T] = conditional(t._1)(t._2)
}
