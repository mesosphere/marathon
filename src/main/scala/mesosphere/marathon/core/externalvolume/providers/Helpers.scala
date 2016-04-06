package mesosphere.marathon.core.externalvolume.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.externalvolume._
import mesosphere.marathon.state._

import scala.util.Try

protected[externalvolume] abstract class AbstractExternalVolumeProvider(
    val name: String) extends ExternalVolumeProvider {
  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def accepts(volume: ExternalVolume): Boolean = {
    volume.external.providerName == name
  }

  override def collect(container: Container): Iterable[ExternalVolume] =
    container.volumes.collect{
      case vol: ExternalVolume if accepts(vol) => vol
    }
}

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
      val parsed: Try[Long] = Try(v.toLong)
      if (parsed.isSuccess && parsed.get > 0) Success
      else Failure(Set(RuleViolation(v, s"Expected a valid, positive integer instead of $v", None)))
    }
  }

  /** a validator to enforce that values parse to booleans */
  import mesosphere.marathon.api.v2.Validation.isTrue
  lazy val validBoolean: Validator[String] = isTrue[String](s"Expected a valid boolean")(s =>
    Try(s.toBoolean).getOrElse(false)
  )
}
