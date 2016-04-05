package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._

protected[volume] abstract class AbstractExternalVolumeProvider(
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

  def validIfDefined[T](implicit validator: Validator[T]): Validator[Option[T]] = new Validator[Option[T]] {
    override def apply(opt: Option[T]): Result = opt match {
      case None    => Success
      case Some(t) => validator(t)
    }
  }

  def definedAnd[T](implicit validator: Validator[T]): Validator[Option[T]] = new Validator[Option[T]] {
    override def apply(opt: Option[T]): Result = opt match {
      case None    => Failure(Set(RuleViolation(None, "not defined", None)))
      case Some(t) => validator(t)
    }
  }

  /** a validator to enforce that values conform to expectations of "labels" */
  lazy val labelValidator: Validator[String] = validator[String] { v =>
    v should matchRegex(LabelRegex)
  }

  /** a validator to enforce that values parse to natural (whole, positive) numbers */
  lazy val naturalNumberValidator: Validator[String] = new Validator[String] {
    override def apply(v: String): Result = {
      import scala.util.Try
      val parsed: Try[Long] = Try(v.toLong)
      if (parsed.isSuccess && parsed.get > 0) Success
      else Failure(Set(RuleViolation(v, s"Expected a valid, positive integer instead of $v", None)))
    }
  }

  /** a validator to enforce that values parse to booleans */
  lazy val booleanValidator: Validator[String] = new Validator[String] {
    override def apply(v: String): Result = {
      import scala.util.Try
      val parsed: Try[Boolean] = Try(v.toBoolean)
      if (parsed.isSuccess) Success
      else Failure(Set(RuleViolation(v, s"Expected a valid boolean instead of $v", None)))
    }
  }
}
