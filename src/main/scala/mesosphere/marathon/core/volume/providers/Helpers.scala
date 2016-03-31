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

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.isDefined && volume.persistent.providerName == name
  }

  override def collect(container: Container): Iterable[PersistentVolume] =
    container.volumes.collect{
      case vol: PersistentVolume if accepts(vol) => vol
    }
}

protected abstract class InjectionHelper[V <: Volume: ClassTag] extends VolumeInjection {

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
