package mesosphere.marathon.core.volume.providers

import com.wix.accord.{ Validator, _ }
import com.wix.accord.dsl._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ Volume => MesosVolume, CommandInfo, ContainerInfo, Environment }

import scala.collection.JavaConverters._

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles external volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected[volume] case object DVDIProvider
    extends AbstractExternalVolumeProvider("dvdi") {

  import org.apache.mesos.Protos.Volume.Mode
  import OptionSupport._

  val validRexRayOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opts =>
    opts.get("dvdi/volumetype") is validIfDefined(labelValidator)
    opts.get("dvdi/newfstype") is validIfDefined(labelValidator)
    opts.get("dvdi/iops") is validIfDefined(naturalNumberValidator)
    opts.get("dvdi/overwritefs") is validIfDefined(booleanValidator)
  }

  val driverOption = "dvdi/driver"

  val volumeValidation = validator[ExternalVolume] { v =>
    v.external.name is notEmpty
    v.external.providerName is equalTo(name)

    v.external.options.get(driverOption) is definedAnd(labelValidator)
    (v.external.options.get(driverOption) is definedAnd(notEqualTo("rexray"))) or
      (v.external.options is valid(validRexRayOptions))
  }

  private def nameOf(vol: ExternalVolumeInfo): Option[String] = {
    Some(vol.providerName + "::" + vol.name)
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

  private def volumesForApp(app: AppDefinition): Iterable[ExternalVolume] =
    app.container.toSet[Container].flatMap(collect)

  // for now this matches the validation for resident tasks, but probably won't be as
  // restrictive in the future.
  val validUpgradeStrategy: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be <= 0.5
    strategy.maximumOverCapacity should equalTo(0.0)
  }

  val appBasicValidation: Validator[AppDefinition] = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val nv = nameViolations(app)
      val iv = instanceViolations(app)
      if (nv.isEmpty && iv.isEmpty) Success
      else Failure(nv.toSet[Violation] ++ iv.toSet)
    }
  }

  def driversInUse(ct: Container): Set[String] =
    collect(ct).flatMap(_.external.options.get(driverOption)).toSet

  /** @return a count of volume references-by-name within an app spec */
  def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    volumesForApp(app).flatMap{ pv => nameOf(pv.external) }.groupBy(identity).mapValues(_.size)

  protected[providers] def modes(ct: Container): Set[Mode] =
    collect(ct).map(_.mode).toSet

  /** @return true if ExternalInfo.size is defined for any DVDI volume in the container */
  protected[providers] def isSizeDefinedForAny(ct: Container): Boolean =
    collect(ct).flatMap(_.external.size).toSet.nonEmpty

  val validMesosContainer: Validator[Container] = validator[Container] { ct =>
    modes(ct).each as "read/write modes specified" is equalTo(Mode.RW)
  }

  val validDockerContainer: Validator[Container] = validator[Container] { ct =>
    // only allow a single docker volume driver to be specified
    driversInUse(ct).size as "count of driver names specified" should equalTo(1)
    // fail validation if/when user specifies "size"
    isSizeDefinedForAny(ct) as "is size defined for any" is equalTo(false)
  }

  /** Only allow a single docker volume driver to be specified w/ the docker containerizer. */
  val containerValidation: Validator[Container] = validator[Container] { ct =>
    (ct.`type` as "container.type" is equalTo(ContainerInfo.Type.MESOS) and (ct is validMesosContainer)) or (
      (ct.`type` as "container.type" is equalTo(ContainerInfo.Type.DOCKER)) and (ct is validDockerContainer)
    )
  }

  val appValidation: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app is appBasicValidation
    app.container.each is containerValidation
    app.upgradeStrategy is validUpgradeStrategy
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation: Validator[Group] = new Validator[Group] {
    override def apply(g: Group): Result = {
      val transitiveApps = g.transitiveApps.toList

      val appsByVolume = g.apps.flatMap { app =>
        volumesForApp(app).flatMap{ vol => nameOf(vol.external).map(_ -> app.id) }
      }.groupBy[String](_._1).mapValues(_.map(_._2))

      val groupViolations = g.apps.flatMap { app =>
        val ruleViolations = volumesForApp(app).flatMap{ vol => nameOf(vol.external) }.flatMap{ name =>
          for {
            otherApp <- appsByVolume(name)
            if otherApp != app.id // do not compare to self
          } yield RuleViolation(app.id,
            s"Requested volume $name conflicts with a volume in app $otherApp", None)
        }
        if (ruleViolations.isEmpty) None
        else Some(GroupViolation(app, "app contains conflicting volumes", None, ruleViolations.toSet))
      }
      if (groupViolations.isEmpty) Success
      else Failure(groupViolations.toSet)
    }
  }

  /** non-agent-local ExternalVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: ExternalVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.external.name)
      .setMode(volume.mode)
      .build

  def build(builder: ContainerInfo.Builder, v: Volume): Unit = v match {
    case pv: ExternalVolume =>
      // special behavior for docker vs. mesos containers
      // - docker containerizer: serialize volumes into mesos proto
      // - docker containerizer: specify "volumeDriver" for the container
      if (builder.getType == ContainerInfo.Type.DOCKER && builder.hasDocker) {
        val driverName = pv.external.options(driverOption)
        builder.setDocker(builder.getDocker.toBuilder.setVolumeDriver(driverName).build)
        builder.addVolumes(toMesosVolume(pv))
      }
    case _ =>
  }

  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, pv: ExternalVolume): Unit = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    if (containerType == ContainerInfo.Type.MESOS) {
      val env = if (builder.hasEnvironment) builder.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      builder.setEnvironment(env.build)
    }
  }

  val dvdiVolumeContainerPath = "DVDI_VOLUME_CONTAINERPATH"
  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  def volumeToEnv(vol: ExternalVolume, i: Iterable[Environment.Variable]): Iterable[Environment.Variable] = {
    import OptionLabelPatterns._

    val suffix = {
      val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
        val ss = s.getName.substring(dvdiVolumeName.length)
        if (ss.length > 0) ss.toInt else 0
      }.foldLeft(-1)((z, i) => if (i > z) i else z)

      if (offset >= 0) (offset + 1).toString else ""
    }

    def mkVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    val vars = Seq[Environment.Variable](
      mkVar(dvdiVolumeContainerPath + suffix, vol.containerPath),
      mkVar(dvdiVolumeName + suffix, vol.external.name),
      mkVar(dvdiVolumeDriver + suffix, vol.external.options(driverOption))
    )

    val optsVar = {
      val prefix: String = name + OptionNamespaceSeparator
      // don't let the user override these
      val ignore = Set(driverOption)
      // external.size trumps any user-specified dvdi/size option
      val opts = vol.external.options ++ Map[String, String](
        vol.external.size.map(prefix + "size" -> _.toString).toList: _*
      )

      // forward all dvdi/* options to the dvdcli driver, stripping the dvdi/ prefix
      // and trimming the values
      opts.filterKeys{ k =>
        k.startsWith(prefix) && !ignore.contains(k.toLowerCase)
      }.map{
        case (k, v) => k.substring(prefix.length) + "=" + v.trim()
      }.mkString(",")
    }

    if (optsVar.isEmpty) vars
    else { vars :+ mkVar(dvdiVolumeOpts + suffix, optsVar) }
  }
}
