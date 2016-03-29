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
    volume.persistent.providerName.isDefined && volume.persistent.providerName.get == name
  }

  override def apply(container: Option[Container]): Iterable[PersistentVolume] =
    container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if accepts(vol) => vol }
    }
}

protected abstract class ContextUpdateHelper[V <: Volume: ClassTag] extends ContextUpdate {

  def accepts(v: V): Boolean

  override protected def updated[C <: BuilderContext](context: C, v: Volume): Option[C] = {
    v match {
      case vol: V if accepts(vol) => {
        context match {
          case cc: ContainerContext => updatedContainer(cc, vol).map(_.asInstanceOf[C])
          case cc: CommandContext   => updatedCommand(cc, vol).map(_.asInstanceOf[C])
        }
      }
      case _ => None
    }
  }
  def updatedContainer(cc: ContainerContext, vol: V): Option[ContainerContext] = None
  def updatedCommand(cc: CommandContext, vol: V): Option[CommandContext] = None
}
