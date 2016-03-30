package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.combinators.Fail
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import scala.reflect.ClassTag

protected trait PersistentVolumeProvider extends VolumeProvider[PersistentVolume] {
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

  protected def accepts(v: PersistentVolume): Boolean = {
    v.persistent.providerName.isDefined && v.persistent.providerName.get == name
  }

  override def apply(container: Option[Container]): Iterable[PersistentVolume] =
    container.fold(Seq.empty[PersistentVolume])(_.volumes.collect{
      case vol: PersistentVolume if accepts(vol) => vol
    })
}

protected abstract class DecoratorHelper[V <: Volume: ClassTag] extends Decorator {
  protected def accepts(v: V): Boolean

  override protected def decorated[C <: DecoratorContext](ctx: C, v: Volume): C = {
    v match {
      case vol: V if accepts(vol) =>
        ctx match {
          case cc: ContainerContext => decoratedContainer(cc, vol).asInstanceOf[C]
          case cc: CommandContext   => decoratedCommand(cc, vol).asInstanceOf[C]
        }
      case _ => ctx
    }
  }
  def decoratedContainer(ctx: ContainerContext, vol: V): ContainerContext = ctx
  def decoratedCommand(ctx: CommandContext, vol: V): CommandContext = ctx
}
