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
protected case object DVDIProvider extends DecoratorHelper[PersistentVolume] with PersistentVolumeProvider {
  import org.apache.mesos.Protos.Volume.Mode

  val name = "dvdi"

  val optionDriver = name + "/driverName"
  val optionIOPS = name + "/iops"
  val optionType = name + "/volumeType"

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
    v.persistent.providerName.each is equalTo(name) // sanity check
    v.persistent.options is notEmpty
    v.persistent.options.each is valid(validOptions)
  }

  def nameOf(vol: PersistentVolumeInfo): Option[String] = {
    if (vol.providerName.isDefined && vol.name.isDefined) {
      Some(vol.providerName.get + "::" + vol.name.get)
    }
    else None
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation: Validator[Group] = new Validator[Group] {
    override def apply(g: Group): Result = {
      val groupViolations = g.apps.flatMap { app =>
        val nameCounts = volumeNameCounts(app)
        val internalNameViolations = {
          nameCounts.filter(_._2 > 1).map{ e =>
            RuleViolation(app.id, s"Requested volume ${e._1} is declared more than once within app ${app.id}", None)
          }
        }
        val instancesViolation: Option[RuleViolation] =
          if (app.instances > 1) Some(RuleViolation(app.id,
            s"Number of instances is limited to 1 when declaring external volumes in app ${app.id}", None))
          else None
        val ruleViolations = DVDIProvider.this.apply(app.container).toSeq.flatMap{ vol =>
          val name = nameOf(vol.persistent)
          if (name.isDefined) {
            for {
              otherApp <- g.transitiveApps.toList
              if otherApp != app.id // do not compare to self
              otherVol <- DVDIProvider.this.apply(otherApp.container)
              otherName <- nameOf(otherVol.persistent)
              if name == otherName
            } yield RuleViolation(app.id,
              s"Requested volume $name conflicts with a volume in app ${otherApp.id}", None)
          }
          else None
        }
        if (internalNameViolations.isEmpty && ruleViolations.isEmpty && instancesViolation.isEmpty) None
        else Some(GroupViolation(app, "app contains conflicting volumes", None,
          internalNameViolations.toSet[Violation] ++ instancesViolation.toSet ++ ruleViolations.toSet))
      }
      if (groupViolations.isEmpty) Success
      else Failure(groupViolations.toSet)
    }
  }

  def driversInUse(ct: Container): Set[String] =
    DVDIProvider.this.apply(Some(ct)).flatMap(_.persistent.options.flatMap(_.get(optionDriver))).toSet

  /** @return a count of volume references-by-name within an app spec */
  def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    DVDIProvider.this.apply(app.container).flatMap{ pv => nameOf(pv.persistent) }.
      groupBy(identity).mapValues(_.size)

  protected[providers] def modes(ct: Container): Set[Mode] =
    DVDIProvider.this.apply(Some(ct)).map(_.mode).toSet

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

  override def decoratedContainer(ctx: ContainerContext, pv: PersistentVolume): ContainerContext = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    val ci = ctx.ci // TODO(jdef) clone?
    if (ci.getType == ContainerInfo.Type.DOCKER && ci.hasDocker) {
      val driverName = pv.persistent.options.get(optionDriver)
      if (ci.getDocker.getVolumeDriver != driverName) {
        ci.setDocker(ci.getDocker.toBuilder.setVolumeDriver(driverName).build)
      }
      ContainerContext(ci.addVolumes(toMesosVolume(pv)))
    }
    else ctx
  }

  override def decoratedCommand(ctx: CommandContext, pv: PersistentVolume): CommandContext = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    val (ct, ci) = (ctx.ct, ctx.ci) // TODO(jdef) clone ci?
    if (ct == ContainerInfo.Type.MESOS) {
      val env = if (ci.hasEnvironment) ci.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      CommandContext(ct, ci.setEnvironment(env.build))
    }
    else ctx
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
      newVar(dvdiVolumeDriver + suffix, v.persistent.options.get(optionDriver))
    // TODO(jdef) support other options here
    )
  }
}
