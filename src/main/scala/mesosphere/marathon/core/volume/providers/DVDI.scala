package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.Validator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, Volume => MesosVolume, Environment }
import scala.collection.JavaConverters._

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles persistent volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected case object DVDIProvider extends InjectionHelper[PersistentVolume] with PersistentVolumeProvider {
  import org.apache.mesos.Protos.Volume.Mode

  override val name = Some("dvdi")

  val optionDriver = name.get + "/driverName"
  val optionIOPS = name.get + "/iops"
  val optionType = name.get + "/volumeType"

  val validOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opt =>
    opt.get(optionDriver) as "driverName option" is notEmpty
    // TODO(jdef) stronger validation for contents of driver name
    opt.get(optionDriver).each as "driverName option" is notEmpty
    // TODO(jdef) validate contents of iops and volume type options
  }

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.name is notEmpty
    v.persistent.name.each is notEmpty
    v.persistent.providerName is notEmpty
    v.persistent.providerName.each is notEmpty
    v.persistent.providerName.each is equalTo(name.get) // sanity check
    v.persistent.options is valid(validOptions)
  }

  private def nameOf(vol: PersistentVolumeInfo): Option[String] = {
    if (vol.providerName.isDefined && vol.name.isDefined) {
      Some(vol.providerName.get + "::" + vol.name.get)
    }
    else None
  }

  private def instanceViolations(app: AppDefinition): Option[RuleViolation] = {
    if (volumesForApp(app).nonEmpty && app.instances > 1)
      Some(RuleViolation(app.id,
        s"Number of instances is limited to 1 when declaring DVDI volumes in app ${app.id}", None))
    else None
  }

  private def nameViolations(app: AppDefinition): Iterable[RuleViolation] =
    volumeNameCounts(app).filter(_._2 > 1).map{ e =>
      RuleViolation(app.id, s"Requested DVDI volume ${e._1} is declared more than once within app ${app.id}", None)
    }

  private def volumesForApp(app: AppDefinition): Iterable[PersistentVolume] =
    app.container.toSet[Container].flatMap(collect)

  // for now this matches the validation for resident tasks, but probably won't be as
  // restrictive in the future.
  val validUpgradeStrategy: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be <= 0.5
    strategy.maximumOverCapacity should be == 0.0
  }

  val appValidation: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app is appBasicValidation
    app.container.each is containerValidation
    app.upgradeStrategy is validUpgradeStrategy
  }

  val appBasicValidation: Validator[AppDefinition] = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val nv = nameViolations(app)
      val iv = instanceViolations(app)
      if (nv.isEmpty && iv.isEmpty) Success
      else Failure(nv.toSet[Violation] ++ iv.toSet)
    }
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation: Validator[Group] = new Validator[Group] {
    override def apply(g: Group): Result = {
      val transitiveApps = g.transitiveApps.toList
      val groupViolations = g.apps.flatMap { app =>
        val ruleViolations = volumesForApp(app).flatMap{ vol => nameOf(vol.persistent) }.flatMap{ name =>
          for {
            otherApp <- transitiveApps
            if otherApp.id != app.id // do not compare to self
            otherVol <- volumesForApp(otherApp)
            otherName <- nameOf(otherVol.persistent)
            if name == otherName
          } yield RuleViolation(app.id,
            s"Requested volume $name conflicts with a volume in app ${otherApp.id}", None)
        }
        if (ruleViolations.isEmpty) None
        else Some(GroupViolation(app, "app contains conflicting volumes", None,
          ruleViolations.toSet))
      }
      if (groupViolations.isEmpty) Success
      else Failure(groupViolations.toSet)
    }
  }

  def driversInUse(ct: Container): Set[String] =
    collect(ct).flatMap(_.persistent.options.get(optionDriver)).toSet

  /** @return a count of volume references-by-name within an app spec */
  def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    volumesForApp(app).flatMap{ pv => nameOf(pv.persistent) }.groupBy(identity).mapValues(_.size)

  protected[providers] def modes(ct: Container): Set[Mode] =
    collect(ct).map(_.mode).toSet

  /** Only allow a single docker volume driver to be specified w/ the docker containerizer. */
  val containerValidation: Validator[Container] = validator[Container] { ct =>
    (ct.`type` is equalTo(ContainerInfo.Type.MESOS) and (modes(ct).each is equalTo(Mode.RW))) or (
      (ct.`type` is equalTo(ContainerInfo.Type.DOCKER)) and (driversInUse(ct).size should equalTo(1))
    )
  }

  /** non-agent-local PersistentVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: PersistentVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.persistent.name.get) // validation should protect us from crashing here since name is req'd
      .setMode(volume.mode)
      .build

  override def injectContainer(ctx: ContainerContext, pv: PersistentVolume): ContainerContext = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    val container = ctx.container // TODO(jdef) clone?
    if (container.getType == ContainerInfo.Type.DOCKER && container.hasDocker) {
      val driverName = pv.persistent.options(optionDriver)
      if (container.getDocker.getVolumeDriver != driverName) {
        container.setDocker(container.getDocker.toBuilder.setVolumeDriver(driverName).build)
      }
      ContainerContext(container.addVolumes(toMesosVolume(pv)))
    }
    super.injectContainer(ctx, pv)
  }

  override def injectCommand(ctx: CommandContext, pv: PersistentVolume): CommandContext = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    val (containerType, command) = (ctx.containerType, ctx.command) // TODO(jdef) clone command?
    if (containerType == ContainerInfo.Type.MESOS) {
      val env = if (command.hasEnvironment) command.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      CommandContext(containerType, command.setEnvironment(env.build))
    }
    super.injectCommand(ctx, pv)
  }

  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Seq[Environment.Variable] = {
    val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
      val ss = s.getName.substring(dvdiVolumeName.size)
      if (ss.length > 0) ss.toInt else 0
    }.foldLeft(-1)((z, i) => if (i > z) i else z)
    val suffix = if (offset >= 0) (offset + 1).toString else ""

    def newVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    Seq(
      newVar(dvdiVolumeName + suffix, v.persistent.name.get),
      newVar(dvdiVolumeDriver + suffix, v.persistent.options(optionDriver))
    // TODO(jdef) support other options here
    )
  }
}
