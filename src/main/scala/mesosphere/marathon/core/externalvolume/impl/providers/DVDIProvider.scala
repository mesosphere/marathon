package mesosphere.marathon.core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.externalvolume.{ ExternalVolumeValidations, ExternalVolumeProvider }
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.Protos.{ Volume => MesosVolume, CommandInfo, ContainerInfo, Environment }

import OptionSupport._
import scala.collection.JavaConverters._

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles external volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected[externalvolume] case object DVDIProvider extends ExternalVolumeProvider {
  override val name: String = "dvdi"

  override def validations: ExternalVolumeValidations = DVDIProviderValidations

  override def build(builder: ContainerInfo.Builder, ev: ExternalVolume): Unit = {
    def toMesosVolume(volume: ExternalVolume): MesosVolume =
      MesosVolume.newBuilder
        .setContainerPath(volume.containerPath)
        .setHostPath(volume.external.name)
        .setMode(volume.mode)
        .build

    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    if (builder.getType == ContainerInfo.Type.DOCKER && builder.hasDocker) {
      val driverName = ev.external.options(driverOption)
      builder.setDocker(builder.getDocker.toBuilder.setVolumeDriver(driverName).build)
      builder.addVolumes(toMesosVolume(ev))
    }
  }

  override def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, ev: ExternalVolume): Unit = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    if (containerType == ContainerInfo.Type.MESOS) {
      val env = if (builder.hasEnvironment) builder.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(ev, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      builder.setEnvironment(env.build)
    }
  }

  val driverOption = "dvdi/driver"
  val dvdiVolumeContainerPath = "DVDI_VOLUME_CONTAINERPATH"
  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  private[providers] def volumeToEnv(
    vol: ExternalVolume,
    i: Iterable[Environment.Variable]): Iterable[Environment.Variable] = {

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

private[externalvolume] object DVDIProviderValidations extends ExternalVolumeValidations {
  import mesosphere.marathon.api.v2.Validation._
  import DVDIProvider._
  import ViolationBuilder._

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  override lazy val rootGroup = new Validator[Group] {
    override def apply(g: Group): Result = {
      val appsByVolume = g.transitiveApps.flatMap { app =>
        app.externalVolumes.flatMap{ vol => nameOf(vol.external).map(_ -> app.id) }
      }.groupBy[String](_._1).mapValues(_.map(_._2))

      val groupViolations = g.apps.flatMap { app =>
        val ruleViolations = app.externalVolumes.flatMap{ vol => nameOf(vol.external) }.flatMap{ name =>
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

  override lazy val app = validator[AppDefinition] { app =>
    //app should haveOnlyOneProvider // TODO(jdef) see comments on validator decl below
    app should haveUniqueExternalVolumeNames
    app should haveOnlyOneReplica
    app.container is valid(optional(validContainer))
    app.upgradeStrategy is valid(validUpgradeStrategy)
  }

  override lazy val volume = validator[ExternalVolume] { v =>
    v.external.name is notEmpty
    v.external.provider is equalTo(name)
    v.external.options.get(driverOption) as s"external/options($driverOption)" is definedAnd(validLabel)
    v.external.options is valid(
      conditional[Map[String, String]](_.get(driverOption).contains("rexray"))(validRexRayOptions))
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  private[this] def accepts(volume: ExternalVolume): Boolean = {
    volume.external.provider == name
  }

  private[this] def optionalOption(opts: Map[String, String], v: Validator[String]): Validator[String] =
    new Validator[String] {
      override def apply(optionName: String): Result = {
        validate(opts.get(optionName))(optional(v)) match {
          case Success     => Success
          // we do this to get sane error messages
          case Failure(vs) => Failure(vs.map(_.withDescription(optionName)))
        }
      }
    }

  private[this] lazy val validRexRayOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opts =>
    // there's probaby a better way to do this; this worked and didn't require me to duplicate option keys
    "dvdi/volumetype" as "" is valid(optionalOption(opts, validLabel))
    "dvdi/newfstype" as "" is valid(optionalOption(opts, validLabel))
    "dvdi/iops" as "" is valid(optionalOption(opts, validNaturalNumber))
    "dvdi/overwritefs" as "" is valid(optionalOption(opts, validBoolean))
  }

  private[this] def nameOf(vol: ExternalVolumeInfo): Option[String] = {
    Some(vol.provider + "::" + vol.name)
  }

  private[this] case object haveOnlyOneReplica extends Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result =
      if (app.externalVolumes.nonEmpty && app.instances > 1)
        Failure(Set(RuleViolation(app.id,
          s"Number of instances is limited to 1 when declaring DVDI volumes in app ${app.id}", None
        )))
      else Success
  }

  private[this] case object haveUniqueExternalVolumeNames extends Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val conflicts = volumeNameCounts(app).filter(_._2 > 1).keys
      if (conflicts.isEmpty) Success
      else Failure(conflicts.toSet[String].map { e =>
        RuleViolation(app.id, s"Requested DVDI volume '$e' is declared more than once within app ${app.id}", None)
      })
    }
  }

  // for now this matches the validation for resident tasks, but probably won't be as
  // restrictive in the future.
  private[this] lazy val validUpgradeStrategy = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be <= 0.5
    strategy.maximumOverCapacity should equalTo(0.0)
  }

  /** @return a count of volume references-by-name within an app spec */
  private[this] def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    app.externalVolumes.flatMap{ v => nameOf(v.external) }.groupBy(identity).mapValues(_.size)

  private[this] lazy val validMesosVolume = validator[ExternalVolume] { v =>
    v.mode is equalTo(Mode.RW)
  }

  private[this] lazy val validMesosContainer = validator[Container] { ct =>
    ct.volumes is every(valid(ifDVDIVolume(validMesosVolume)))
  }

  /*
  // TODO(jdef) Unclear where this requirement came from. Seems super-useful to mix and match
  // volumes from different providers within the same app (we only have 1 right now anyway, but
  // the limitation imposed by this validator doesn't make sense to me).
  private[this] case object haveOnlyOneProvider extends Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val uniqueProviderNames = app.externalVolumes.iterator.map(_.external.provider).toSet
      val n = uniqueProviderNames.size
      if (n <= 1) Success
      else Failure(Set(RuleViolation(n, "one external volume provider per app", None)))
    }
  }
  */

  private[this] case object haveOnlyDriverOption extends Validator[ExternalVolume] {
    override def apply(v: ExternalVolume): Result =
      if (v.external.options.filterKeys(_ != driverOption).isEmpty) Success
      else Failure(Set(RuleViolation(v.external.options, "only contains driver", Some("external.options"))))
  }

  private[this] lazy val haveNoSize: Validator[ExternalVolume] = new NullSafeValidator(
    test = _.external.size.isEmpty,
    failure = v => RuleViolation(v.external.size, "must be undefined for DOCKER container", Some("external/size"))
  )

  private[this] def ifDVDIVolume(vtor: Validator[ExternalVolume]): Validator[Volume] = new Validator[Volume] {
    override def apply(v: Volume): Result = v match {
      case ev: ExternalVolume if accepts(ev) => validate(ev)(vtor)
      case _                                 => Success
    }
  }

  private[this] lazy val validDockerContainer = validator[Container] { ct =>
    ct.volumes is every(valid(ifDVDIVolume(haveOnlyDriverOption)))
    ct.volumes is every(valid(ifDVDIVolume(haveNoSize)))
  }

  private[this] case object validContainer extends Validator[Container] {
    override def apply(ct: Container): Result = ct.`type` match {
      case ContainerInfo.Type.MESOS  => validate(ct)(validMesosContainer)
      case ContainerInfo.Type.DOCKER => validate(ct)(validDockerContainer)
    }
  }
}
