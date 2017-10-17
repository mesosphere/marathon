package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.state._

import scala.util.Try

protected[providers] object OptionSupport {
  import OptionLabelPatterns._

  /** a validator to enforce that values conform to expectations of "labels" */
  lazy val validLabel: Validator[String] = validator[String] { v =>
    v should matchRegex(LabelRegex)
  }

  /** a validator to enforce that values parse to natural (whole, positive) numbers */
  lazy val validNaturalNumber: Validator[String] = new Validator[String] {
    override def apply(v: String): Result = {
      import scala.util.Try
      val parsed = Try(v.toLong)
      parsed match {
        case util.Success(x) if x > 0 => Success
        case _ => Failure(Set(RuleViolation(v, s"Expected a valid, positive integer instead of $v")))
      }
    }
  }

  /** a validator to enforce that values parse to booleans */
  import mesosphere.marathon.api.v2.Validation.isTrue
  lazy val validBoolean: Validator[String] = isTrue[String]("Expected a valid boolean")(s =>
    Try(s.toBoolean).getOrElse(false)
  )
}
