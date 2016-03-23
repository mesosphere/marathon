package mesosphere.marathon.core.volume

import com.wix.accord._
import com.wix.accord.combinators.{ Fail, NilValidator }
import com.wix.accord.dsl._
import com.wix.accord.Validator
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.{ DockerVolume, PersistentVolume, Volume }
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo, Volume => MesosVolume, Environment }
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

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
  protected def make[_ <: Volume](prov: VolumeProvider[_ <: Volume]*): Map[String, VolumeProvider[_ <: Volume]] = {
    prov.foldLeft(Map.empty[String, VolumeProvider[_ <: Volume]]) { (m, p) => m + (p.name -> p) }
  }

  protected val registry = make(
    // list supported providers here
    AgentVolumeProvider,
    DockerHostVolumeProvider,
    DVDIProvider
  )

  protected def providerForName(name: Option[String]): Option[VolumeProvider[_ <: Volume]] =
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

  protected val optionDriver = name + "/driverName"
  protected val optionIOPS = name + "/iops"
  protected val optionType = name + "/volumeType"

  protected val validOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opt =>
    opt.get(optionDriver) as "driverName option" is notEmpty
    // TODO(jdef) stronger validation for contents of driver name
    opt.get(optionDriver).each as "driverName option" is notEmpty
    // TODO(jdef) validate contents of iops and volume type options
  }

  protected val validPersistentVolume = validator[PersistentVolume] { v =>
    // don't invoke validator on v because that's circular, just check the additional
    // things that we need for agent local volumes.
    // see implicit validator in the PersistentVolume class for reference.
    v.persistent.name is notEmpty
    v.persistent.name.each is notEmpty
    v.persistent.providerName is notEmpty
    v.persistent.providerName.each is notEmpty
    v.persistent.providerName.each is equalTo(name) // sanity check
    v.persistent.options is notEmpty
    v.persistent.options.each is valid(validOptions)
  }

  protected val notPersistentVolume = new Fail[Volume]("is not a persistent volume")

  // TODO(jdef) implement me; probably need additional context for validation here because,
  // for example, we only allow a single docker volume driver to be specified w/ the docker
  // containerizer (and we don't know which containerizer is used at this point!)
  val validation = new Validator[Volume] {
    override def apply(v: Volume): Result = v match {
      // sanity check
      case pv: PersistentVolume => validate(pv)(validPersistentVolume)
      case _                    => validate(v)(notPersistentVolume)
    }
  }

  /** non-agent-local PersistentVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: PersistentVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.persistent.name.get) // validation should protect us from crashing here since name is req'd
      .setMode(volume.mode)
      .build

  // special behavior for docker vs. mesos containers
  // - docker containerizer: serialize volumes into mesos proto
  // - docker containerizer: specify "volumeDriver" for the container
  override def containerInfo(v: Volume, ci: ContainerInfo.Builder): Option[ContainerInfo.Builder] =
    if (ci.getType == ContainerInfo.Type.DOCKER && ci.hasDocker)
      Some(v).collect{
        case pv: PersistentVolume if isDVDI(pv) => {
          val driverName = pv.persistent.options.get(optionDriver)
          if (ci.getDocker.getVolumeDriver != driverName) {
            ci.setDocker(ci.getDocker.toBuilder.setVolumeDriver(driverName).build)
          }
          ci.addVolumes(toMesosVolume(pv))
        }
      }
    else None

  protected val dvdiVolumeName = "DVDI_VOLUME_NAME"
  protected val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  protected val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  protected def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Seq[Environment.Variable] = {
    val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
      val ss = s.getName.substring(dvdiVolumeName.size)
      if (ss.length > 0) ss.toInt else 0
    }.foldLeft(-1)((z, i) => if (i > z) i else z)
    val suffix = if (offset >= 0) (offset + 1).toString else ""

    def newVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    Seq(
      newVar(dvdiVolumeName + suffix, v.persistent.name.get),
      newVar(dvdiVolumeDriver + suffix, v.persistent.options.get(optionDriver))
    // TODO(jdef) support other options here
    )
  }

  // special behavior for docker vs. mesos containers
  // - mesos containerizer: serialize volumes into envvar sets
  override def commandInfo(v: Volume, ct: ContainerInfo.Type, ci: CommandInfo.Builder): Option[CommandInfo.Builder] =
    if (ct == ContainerInfo.Type.MESOS)
      Some(v).collect{
        case pv: PersistentVolume if isDVDI(pv) => {
          val env = if (ci.hasEnvironment) ci.getEnvironment.toBuilder else Environment.newBuilder
          val toAdd = volumeToEnv(pv, env.getVariablesList)
          env.addAllVariables(toAdd.asJava)
          ci.setEnvironment(env.build)
        }
      }
    else None

  def isDVDI(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.isDefined && volume.persistent.providerName.get == name
  }

  override def apply(app: AppDefinition): Iterable[PersistentVolume] =
    app.container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if isDVDI(vol) => vol }
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

  protected val validPersistentVolume = validator[PersistentVolume] { v =>
    // don't invoke validator on v because that's circular, just check the additional
    // things that we need for agent local volumes.
    // see implicit validator in the PersistentVolume class for reference.
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  protected val notPersistentVolume = new Fail[Volume]("is not a persistent volume")

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
