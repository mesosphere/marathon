package mesosphere.marathon
package plugin.validation

import com.wix.accord._
import mesosphere.marathon.plugin.RunSpec
import mesosphere.marathon.plugin.plugin.Plugin

/**
  * RunSpecValidator defines a high level interface for RunSpec validation.
  * A RunSpec is only "accepted" by the system once it passes all validation checks.
  */
trait RunSpecValidator extends Validator[RunSpec] with Plugin

object RunSpecValidator {
  implicit def every[T](implicit validator: Validator[T]): Validator[Iterable[T]] = {
    // TODO(jdef) copied this func from marathon api/Validation.scala; would be nice to extract that
    // into a shared validations subproject.
    new Validator[Iterable[T]] {
      override def apply(seq: Iterable[T]): Result = {

        val violations = seq.view.map(item => (item, validator(item))).zipWithIndex.collect {
          case ((item, f: Failure), pos: Int) => GroupViolation(item, "not valid", f.violations, Descriptions.Indexed(pos.toLong))
        }

        if (violations.isEmpty) Success
        else Failure(Set(GroupViolation(seq, "Seq contains elements, which are not valid.", violations.toSet)))
      }
    }
  }

  def isTrue[T](constraint: T => String)(test: T => Boolean): Validator[T] = new Validator[T] {
    // TODO(jdef) copied this func from marathon api/Validation.scala; would be nice to extract that
    // into a shared validations subproject.
    import ViolationBuilder._
    override def apply(value: T): Result = {
      if (test(value)) Success else RuleViolation(value, constraint(value))
    }
  }
}
