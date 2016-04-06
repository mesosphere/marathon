package mesosphere.marathon.core.volume.providers

import com.wix.accord.{ Validator, _ }
import com.wix.accord.dsl._
import mesosphere.marathon.core.volume.providers.DVDIProvider._
import mesosphere.marathon.core.volume.providers.OptionSupport._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
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
    extends AbstractExternalVolumeProvider("dvdi") with DVDIProviderValidations {
  
  val driverOption = "dvdi/driver"

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

protected[volume] trait DVDIProviderValidations {
  private val validRexRayOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opts =>
    opts.get("dvdi/volumetype") is ifDefined(validLabel)
    opts.get("dvdi/newfstype") is ifDefined(validLabel)
    opts.get("dvdi/iops") is ifDefined(validNaturalNumber)
    opts.get("dvdi/overwritefs") is ifDefined(validBoolean)
  }

  private def nameOf(vol: ExternalVolumeInfo): Option[String] = {
    Some(vol.providerName + "::" + vol.name)
  }

  val volumeValidation = validator[ExternalVolume] { v =>
    v.external.name is notEmpty
    v.external.providerName is equalTo(name)

    v.external.options.get(driverOption) as s"external/options($driverOption)" is definedAnd(validLabel)

    v.external.options is validIf[Map[String, String]](_.get(driverOption) == "rexray")(validRexRayOptions)
  }

  private val haveOnlyOneReplica = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result =
      if (volumesForApp(app).nonEmpty && app.instances > 1)
        Failure(Set(RuleViolation(app.id,
          s"Number of instances is limited to 1 when declaring DVDI volumes in app ${app.id}", None
        )))
      else Success
  }

  private val haveUniqueExternalVolumeNames = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val conflicts = volumeNameCounts(app).filter(_._2 > 1).keys
      if (conflicts.isEmpty) Success
      else Failure(conflicts.toSet[String].map { e =>
        RuleViolation(app.id, s"Requested DVDI volume ${e} is declared more than once within app ${app.id}", None)
      })
    }
  }

  private def volumesForApp(app: AppDefinition): Iterable[ExternalVolume] =
    app.container.toSet[Container].flatMap(collect)

  // for now this matches the validation for resident tasks, but probably won't be as
  // restrictive in the future.
  private val validUpgradeStrategy = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be <= 0.5
    strategy.maximumOverCapacity should equalTo(0.0)
  }

  /** @return a count of volume references-by-name within an app spec */
  private def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    volumesForApp(app).flatMap{ v => nameOf(v.external) }.groupBy(identity).mapValues(_.size)

  protected[providers] def modes(ct: Container): Set[Mode] =
    collect(ct).map(_.mode).toSet

  private val validMesosVolume = validator[ExternalVolume] { v =>
    v.mode is equalTo(Mode.RW)
  }

  private val validMesosContainer = validator[Container] { ct =>
    ct.volumes.each is ifDVDIVolume(validMesosVolume)
  }

  private val haveOnlyOneDriver = new Validator[Container] {
    override def apply(ct: Container): Result = {
      val n = collect(ct).flatMap(_.external.options.get(driverOption)).toSet.size
      if (n <= 1) Success
      else Failure(Set(RuleViolation(n, "one external volume driver per app", None)))
    }
  }

  private val haveOnlyDriverOption = new Validator[ExternalVolume] {
    override def apply(v: ExternalVolume): Result =
      if (v.external.options.filterKeys(_ != driverOption).isEmpty) Success
      else Failure(Set(RuleViolation(v.external.options, "only contains driver", Some("external.options"))))
  }

  private val haveNoSize = new Validator[ExternalVolume] {
    override def apply(v: ExternalVolume): Result =
      if (v.external.size.isEmpty) Success
      else Failure(Set(RuleViolation(v.external.size, "must be undefined", Some("external/size"))))
  }

  private def ifDVDIVolume(vtor: Validator[ExternalVolume]) = new Validator[Volume] {
    override def apply(v: Volume): Result = v match {
      case ev: ExternalVolume if accepts(ev) => vtor(ev)
      case _ => Success
    }
  }

  private val validDockerContainer = validator[Container] { ct =>
    ct should haveOnlyOneDriver
    ct.volumes.each should ifDVDIVolume(haveOnlyDriverOption)
    ct.volumes.each should ifDVDIVolume(haveNoSize)
  }

  private val validContainer = new Validator[Container] {
    override def apply(ct: Container): Result = ct.`type` match {
      case ContainerInfo.Type.MESOS => validMesosContainer(ct)
      case ContainerInfo.Type.DOCKER => validDockerContainer(ct)
    }
  }

  val appValidation = validator[AppDefinition] { app =>
    app should haveUniqueExternalVolumeNames
    app should haveOnlyOneReplica
    app.container is ifDefined(validContainer)
    app.upgradeStrategy is validUpgradeStrategy
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation = new Validator[Group] {
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
}