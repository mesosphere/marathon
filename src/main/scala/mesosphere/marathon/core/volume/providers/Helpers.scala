package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.combinators.Fail
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._

protected[volume] abstract class AbstractPersistentVolumeProvider(
    val name: String
) extends PersistentVolumeProvider[PersistentVolume] {
  /**
    * don't invoke validator on v because that's circular, just check the additional
    * things that we need for agent local volumes.
    * see implicit validator in the PersistentVolume class for reference.
    */
  val validPersistentVolume: Validator[PersistentVolume]

  /** convenience validator that type-checks for persistent volume */
  val validation = new Validator[Volume] {
    val notPersistentVolume = new Fail[Volume]("is not a persistent volume")
    override def apply(v: Volume): Result = v match {
      case pv: PersistentVolume => validate(pv)(validPersistentVolume)
      case _                    => validate(v)(notPersistentVolume)
    }
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.isDefined && volume.persistent.providerName.contains(name)
  }

  override def collect(container: Container): Iterable[PersistentVolume] =
    container.volumes.collect{
      case vol: PersistentVolume if accepts(vol) => vol
    }
}

protected trait OptionSupport {
  import OptionLabelPatterns._

  /** NamedOption represents a (named) configurable item type that provides validation rules */
  trait NamedOption {
    val namespace: String
    val name: String
    val validValue: Validator[String]
    def required: Boolean = false
    def fullName: String = namespace + OptionNamespaceSeparator + name
    def from(m: Map[String, String]): Option[String] = m.get(fullName)

    def validOption: Validator[Map[String, String]] = new Validator[Map[String, String]] {
      override def apply(m: Map[String, String]): Result = from(m).map(validValue).getOrElse(
        if (required) Failure(Set(RuleViolation(fullName, "is a required option, but is not present", None)))
        else Success
      )
    }
  }

  trait RequiredOption extends NamedOption {
    override def required: Boolean = true
  }

  /** supply a validator to enforce that values conform to expectations of "labels" */
  trait NamedLabelOption extends NamedOption {
    override val validValue: Validator[String] = validator[String] { v =>
      v should matchRegex(LabelRegex)
    }
  }

  /** supply a validator to enforce that values parse to natural (whole, positive) numbers */
  trait NamedNaturalNumberOption extends NamedOption {
    import scala.util.Try
    override val validValue: Validator[String] = new Validator[String] {
      override def apply(v: String): Result = {
        val parsed: Try[Long] = Try(v.toLong)
        if (parsed.isSuccess && parsed.get > 0) Success
        else Failure(Set(RuleViolation(v, s"Expected a valid, positive integer instead of $v", None)))
      }
    }
  }

  /** supply a validator to enforce that values parse to booleans */
  trait NamedBooleanOption extends NamedOption {
    import scala.util.Try
    override val validValue: Validator[String] = new Validator[String] {
      override def apply(v: String): Result = {
        val parsed: Try[Boolean] = Try(v.toBoolean)
        if (parsed.isSuccess) Success
        else Failure(Set(RuleViolation(v, s"Expected a valid boolean instead of $v", None)))
      }
    }
  }
}

protected trait InjectionHelper[V <: Volume] extends VolumeInjection {
  def accepts(v: V): Boolean

  override protected def inject[C <: InjectionContext](ctx: C, v: Volume): C = {
    v match {
      case vol: V if accepts(vol) => {
        ctx match {
          case cc: ContainerContext => injectContainer(cc, vol).asInstanceOf[C]
          case cc: CommandContext   => injectCommand(cc, vol).asInstanceOf[C]
        }
      }
      case _ => super.inject(ctx, v)
    }
  }
  def injectContainer(ctx: ContainerContext, vol: V): ContainerContext = ctx
  def injectCommand(ctx: CommandContext, vol: V): CommandContext = ctx
}
