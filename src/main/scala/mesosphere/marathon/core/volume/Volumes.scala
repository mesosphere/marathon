package mesosphere.marathon.core.volume

import com.wix.accord._
import com.wix.accord.combinators.{ Fail, NilValidator }
import com.wix.accord.dsl._
import com.wix.accord.Validator
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.{ DockerVolume, PersistentVolume, Volume }
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo, Volume => MesosVolume }

/**
  * VolumeProvider is an interface implemented by storage volume providers
  */
sealed trait VolumeProvider[T <: Volume] {
  /** name uniquely identifies this volume provider */
  val name: String
  /** validation implements this provider's specific validation rules */
  val validation: Validator[Volume]

  /** apply scrapes volumes from an application definition that are supported this volume provider */
  def apply(app: AppDefinition): Iterable[T]
}

/**
  * VolumeProvider (companion?)
  * TODO(jdef) this should probably be renamed RegistryImpl or something
  */
protected object VolumeProvider extends VolumeProviderRegistry {
  // TODO(jdef) this declaration is crazy. there must be a better way
  private[this] def make[_ <: Volume](prov: VolumeProvider[_ <: Volume]*): Map[String, VolumeProvider[_ <: Volume]] = {
    prov.foldLeft(Map.empty[String, VolumeProvider[_ <: Volume]]) { (m, p) => m + (p.name -> p) }
  }

  private[this] val registry = make(
    // list supported providers here
    AgentVolumeProvider,
    DockerHostVolumeProvider,
    DVDIProvider
  )

  private def providerForName(name: Option[String]): Option[VolumeProvider[_ <: Volume]] =
    registry.get(name.getOrElse(AgentVolumeProvider.name))

  override def apply[T <: Volume](v: T): Option[VolumeProvider[T]] =
    v match {
      case dv: DockerVolume     => Some(DockerHostVolumeProvider.asInstanceOf[VolumeProvider[T]])
      case pv: PersistentVolume => providerForName(pv.persistent.providerName).map(_.asInstanceOf[VolumeProvider[T]])
    }

  override def apply(name: Option[String]): Option[VolumeProvider[_ <: Volume]] =
    providerForName(name)

  override def known(): Validator[Option[String]] =
    new NullSafeValidator[Option[String]](
      test = { !apply(_).isEmpty },
      failure = _ -> s"is not one of (${registry.keys.mkString(",")})"
    )

  override def approved[T <: Volume](name: Option[String]): Validator[T] =
    apply(name).fold(new Fail[T]("is an illegal volume specification").asInstanceOf[Validator[T]])(_.validation)
}

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles persistent volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected object DVDIProvider
    extends VolumeProvider[PersistentVolume]
    with VolumeBuilderSupport {
  val name = "dvdi"

  // TODO(jdef) implement me; probably need additional context for validation here because,
  // for example, we only allow a single docker volume driver to be specified w/ the docker
  // containerizer (and we don't know which containerizer is used at this point!)
  val validation: Validator[Volume] = new Fail[Volume]("is not yet implemented")

  // TODO(jdef) special behavior for docker vs. mesos containers
  // - docker containerizer: serialize volumes into mesos proto
  // - docker containerizer: specify "volumeDriver" for the container
  override def containerInfo(v: Volume, ci: ContainerInfo.Builder): Option[ContainerInfo.Builder] = None

  // TODO(jdef) special behavior for docker vs. mesos containers; we probably need another
  // parameter here (like container type)
  // - mesos containerizer: serialize volumes into envvar sets
  override def commandInfo(v: Volume, ci: CommandInfo.Builder): Option[CommandInfo.Builder] = None

  // TODO(jdef) this and the func below were copy pasted from AgentProvider .. should probably refactor
  def isAgentLocal(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.getOrElse(name) == name
  }

  override def apply(app: AppDefinition): Iterable[PersistentVolume] =
    app.container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if isAgentLocal(vol) => vol }
    }
}

/**
  * DockerHostVolumeProvider handles Docker volumes that a user would like to mount at
  * predetermined host and container paths. Docker host volumes are not intended to be used
  * with "non-local" docker volume drivers. If you want to use a docker volume driver then
  * use a PersistentVolume instead.
  */
protected object DockerHostVolumeProvider
    extends VolumeProvider[DockerVolume]
    with VolumeBuilderSupport {
  val name = "docker" // only because we should have a non-empty name

  /** no special case validation here, it's handled elsewhere */
  val validation: Validator[Volume] = new NilValidator[Volume]

  /** DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: DockerVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build

  /** @return a possibly modified builder if `v` is a DockerVolume */
  override def containerInfo(v: Volume, ci: ContainerInfo.Builder): Option[ContainerInfo.Builder] = {
    Some(v).collect{ case dv: DockerVolume => ci.addVolumes(toMesosVolume(dv)) }
  }

  override def apply(app: AppDefinition): Iterable[DockerVolume] =
    app.container.fold(Seq.empty[DockerVolume])(_.volumes.collect{ case vol: DockerVolume => vol })
}

/**
  * AgentVolumeProvider handles persistent volumes allocated from agent resources.
  */
protected object AgentVolumeProvider extends VolumeProvider[PersistentVolume] with LocalVolumes {
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

  override def apply(app: AppDefinition): Iterable[PersistentVolume] =
    app.container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if isAgentLocal(vol) => vol }
    }

  override def local(app: AppDefinition): Iterable[Task.LocalVolume] = {
    apply(app).map{ volume => Task.LocalVolume(Task.LocalVolumeId(app.id, volume), volume) }
  }

  override def diskSize(app: AppDefinition): Double = {
    apply(app).map(_.persistent.size).flatten.sum.toDouble
  }
}
