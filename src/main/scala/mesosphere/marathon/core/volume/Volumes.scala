package mesosphere.marathon.core.volume

import com.wix.accord._
import com.wix.accord.combinators.Fail
import com.wix.accord.dsl._
import com.wix.accord.Validator
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Volume
import mesosphere.marathon.state.PersistentVolume

/**
  * Volumes is an interface implemented by storage volume providers
  */
sealed trait Volumes[T <: Volume] {
  /** name uniquely identifies this volume provider */
  val name: String
  /** validation implements this provider's specific validation rules */
  val validation: Validator[Volume]

  /** apply scrapes volumes from an application definition that are supported this volume provider */
  def apply(app: AppDefinition): Iterable[T]
}

trait LocalVolumes {
  /** @return a stream of task local volumes, extrapolating them from the app spec */
  def local(app: AppDefinition): Iterable[Task.LocalVolume]
}

object Volumes {
  // TODO(jdef) this declaration is crazy. there must be a better way
  private[this] def makeRegistry[_ <: Volume](providers: Volumes[_ <: Volume]*): Map[String, Volumes[_ <: Volume]] = {
    providers.foldLeft(Map.empty[String, Volumes[_ <: Volume]]) { (m, p) => m + (p.name -> p) }
  }

  private[this] val registry = makeRegistry(
    // list supported providers here
    AgentVolumes
  )

  /**
    * @return the Volumes interface registered for the given name; if name is None then
    * the default Volumes implementation is returned. None is returned if Some name is given
    * but no volume provider is registered for that name.
    */
  def apply(name: Option[String]): Option[Volumes[_ <: Volume]] =
    registry.get(name.getOrElse(AgentVolumes.name))

  /** @return a validator that checks the validity of a volume provider name */
  def knownProvider(): Validator[Option[String]] =
    new NullSafeValidator[Option[String]](
      test = { !apply(_).isEmpty },
      failure = _ -> s"is not one of (${registry.keys.mkString(",")})"
    )

  /** @return a validator that checks the validity of a volume given the volume provider name */
  def approved[T <: Volume](name: Option[String]): Validator[T] =
    apply(name).fold(new Fail[T]("is an illegal volume specification").asInstanceOf[Validator[T]])(_.validation)
}

object AgentVolumes extends Volumes[PersistentVolume] with LocalVolumes {
  import org.apache.mesos.Protos.Volume.Mode
  import mesosphere.marathon.api.v2.Validation._

  /** this is the name of the agent volume provider */
  val name = "agent"

  private val validPersistentVolume = validator[PersistentVolume] { v =>
    // don't invoke validator on v because that's circular, just check the additional
    // things that we need for agent local volumes.
    // see implicit validator in the PersistentVolume class for reference.
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  private val notPersistentVolume = new Fail[Volume]("is not a persistent volume")

  /** validation checks that size has been specified */
  val validation = new Validator[Volume] {
    override def apply(v: Volume): Result = v match {
      // sanity check
      case pv: PersistentVolume => validate(pv)(validPersistentVolume)
      case _                    => validate(v)(notPersistentVolume)
    }
  }

  def isAgentLocal(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.getOrElse(name) == name
  }

  override def apply(app: AppDefinition): Iterable[PersistentVolume] = {
    app.persistentVolumes.filter(isAgentLocal)
  }

  /** @return a stream of task local volumes, extrapolating them from the app spec */
  override def local(app: AppDefinition): Iterable[Task.LocalVolume] = {
    apply(app).map{ volume => Task.LocalVolume(Task.LocalVolumeId(app.id, volume), volume) }
  }

  /** @return the aggregate mesos disk resources required for volumes */
  def diskSize(app: AppDefinition): Double = {
    apply(app).map(_.persistent.size).flatten.sum.toDouble
  }
}
