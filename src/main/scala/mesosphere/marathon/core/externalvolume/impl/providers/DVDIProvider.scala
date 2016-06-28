package mesosphere.marathon.core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.externalvolume.impl.{ ExternalVolumeValidations, ExternalVolumeProvider }
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.Protos.{ Volume => MesosVolume, ContainerInfo, Parameter, Parameters }

import OptionSupport._
import scala.collection.JavaConverters._
import scala.collection.immutable.Set

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles external volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
private[impl] case object DVDIProvider extends ExternalVolumeProvider {
  override val name: String = "dvdi"

  override def validations: ExternalVolumeValidations = DVDIProviderValidations

  object Builders {
    /**
      * see [[mesosphere.marathon.api.serialization.ContainerSerializer]].
      */
    def toDockerizedMesosVolume(volume: ExternalVolume): MesosVolume =
      MesosVolume.newBuilder
        .setContainerPath(volume.containerPath)
        .setHostPath(volume.external.name)
        .setMode(volume.mode)
        .build

    def dockerVolumeParameters(volume: ExternalVolume): Seq[Parameter] = {
      import OptionLabelPatterns._
      val prefix: String = name + OptionNamespaceSeparator
      // don't let the user override these
      val ignore = Set(driverOption)
      // external.size trumps any user-specified dvdi/size option
      val opts = volume.external.options ++ Map[String, String](
        volume.external.size.map(prefix + "size" -> _.toString).toList: _*
      )

      // forward all dvdi/* options to the dvdcli driver, stripping the dvdi/ prefix
      // and trimming the values
      opts.filterKeys{ k =>
        k.startsWith(prefix) && !ignore.contains(k.toLowerCase)
      }.map {
        case (k, v) => Parameter.newBuilder
          .setKey(k.substring(prefix.length))
          .setValue(v.trim())
          .build
      }.toSeq
    }

    def applyOptions(dv: MesosVolume.Source.DockerVolume.Builder, opts: Seq[Parameter]): Unit = {
      if (opts.isEmpty) {
        // explicitly clear the options field if there are none to add; a nil parameters field is
        // semantically different than an empty one.
        dv.clearDriverOptions
      } else {
        dv.setDriverOptions(Parameters.newBuilder.addAllParameter(opts.asJava))
      }
    }

    def toUnifiedMesosVolume(volume: ExternalVolume): MesosVolume = {
      val driverName = volume.external.options(driverOption)
      val volBuilder = MesosVolume.Source.DockerVolume.newBuilder
        .setDriver(driverName)
        .setName(volume.external.name)
      applyOptions(volBuilder, dockerVolumeParameters(volume))
      MesosVolume.newBuilder
        .setContainerPath(volume.containerPath)
        .setMode(volume.mode)
        .setSource(MesosVolume.Source.newBuilder
          .setType(MesosVolume.Source.Type.DOCKER_VOLUME)
          .setDockerVolume(volBuilder.build)
        ).build
    }
  } // Builders

  override def build(builder: ContainerInfo.Builder, ev: ExternalVolume): Unit = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    if (builder.getType == ContainerInfo.Type.DOCKER && builder.hasDocker) {
      val driverName = ev.external.options(driverOption)
      builder.setDocker(builder.getDocker.toBuilder.setVolumeDriver(driverName).build)
      builder.addVolumes(Builders.toDockerizedMesosVolume(ev))
    } else if (builder.getType == ContainerInfo.Type.MESOS) {
      builder.addVolumes(Builders.toUnifiedMesosVolume(ev))
    }
  }

  val driverOption = "dvdi/driver"
  val quotedDriverOption = '"' + driverOption + '"'
}

private[impl] object DVDIProviderValidations extends ExternalVolumeValidations {
  import mesosphere.marathon.api.v2.Validation._
  import DVDIProvider._

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  override lazy val rootGroup = new Validator[Group] {
    override def apply(g: Group): Result = {
      val appsByVolume: Map[String, Set[PathId]] =
        g.transitiveApps
          .flatMap { app => namesOfMatchingVolumes(app).map(_ -> app.id) }
          .groupBy { case (volumeName, _) => volumeName }
          .mapValues(_.map { case (volumeName, appId) => appId })

      val appValid: Validator[AppDefinition] = {
        def volumeNameUnique(appId: PathId): Validator[ExternalVolume] = {
          def conflictingApps(vol: ExternalVolume): Set[PathId] =
            appsByVolume.getOrElse(vol.external.name, Set.empty).filter(_ != appId)

          isTrue { (vol: ExternalVolume) =>
            val conflictingAppIds = conflictingApps(vol).mkString(", ")
            s"Volume name '${vol.external.name}' in $appId conflicts with volume(s) of same name in app(s): " +
              s"$conflictingAppIds"
          }{ vol => conflictingApps(vol).isEmpty }
        }

        validator[AppDefinition] { app =>
          app.externalVolumes is every(volumeNameUnique(app.id))
        }
      }

      def groupValid: Validator[Group] = validator[Group] { group =>
        group.apps is every(appValid)
        group.groups is every(groupValid)
      }

      // We need to call the validators recursively such that the "description" of the rule violations
      // is correctly calculated.
      groupValid(g)
    }

  }

  override lazy val app = {
    val haveOnlyOneInstance: Validator[AppDefinition] =
      isTrue[AppDefinition](
        (app: AppDefinition) => s"Number of instances is limited to 1 when declaring DVDI volumes in app [$app.id]"
      ) {
          _.instances <= 1
        }

    case object haveUniqueExternalVolumeNames extends Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        val conflicts = volumeNameCounts(app).filter { case (volumeName, number) => number > 1 }.keys
        group(
          conflicts.toSet[String].map { e =>
            RuleViolation(app.id, s"Requested DVDI volume '$e' is declared more than once within app ${app.id}", None)
          }
        )
      }

      /** @return a count of volume references-by-name within an app spec */
      def volumeNameCounts(app: AppDefinition): Map[String, Int] =
        namesOfMatchingVolumes(app).groupBy(identity).mapValues(_.size)
    }

    val validContainer = {
      import PathPatterns._

      val validMesosVolume = validator[ExternalVolume] {
        volume =>
          volume.mode is equalTo(Mode.RW)
          volume.containerPath is notOneOf(DotPaths: _*)
          // same as for local persistent volumes
          volume.containerPath should matchRegexFully(NoSlashesPattern)
      }

      val validDockerVolume = validator[ExternalVolume] { volume =>
        volume.external.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
        volume.external.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
        volume.containerPath is notOneOf(DotPaths: _*)
        // TODO(jdef) change this once docker containerizer supports relative containerPaths
        volume.containerPath should matchRegexFully(AbsolutePathPattern)
      }

      def ifDVDIVolume(vtor: Validator[ExternalVolume]): Validator[ExternalVolume] = conditional(matchesProvider)(vtor)

      def volumeValidator(`type`: ContainerInfo.Type) = `type` match {
        case ContainerInfo.Type.MESOS => validMesosVolume
        case ContainerInfo.Type.DOCKER => validDockerVolume
      }

      validator[Container] { ct =>
        ct.volumes.collect { case ev: ExternalVolume => ev } as "volumes" is
          every(ifDVDIVolume(volumeValidator(ct.`type`)))
      }
    }

    validator[AppDefinition] { app =>
      app should haveUniqueExternalVolumeNames
      app should haveOnlyOneInstance
      app.container is valid(optional(validContainer))
      app.upgradeStrategy is valid(UpgradeStrategy.validForResidentTasks)
    }
  }

  override lazy val volume = {
    def optionalOption(options: Map[String, String], optionValidator: Validator[String]): Validator[String] =
      validator[String] { optionName => options.get(optionName) is optional(optionValidator) }

    val validRexRayOptions: Validator[Map[String, String]] = {
      mapDescription(description => s"($description)") {
        validator[Map[String, String]] { opts =>
          "dvdi/volumetype" is optionalOption(opts, validLabel)
          "dvdi/newfstype" is optionalOption(opts, validLabel)
          "dvdi/iops" is optionalOption(opts, validNaturalNumber)
          "dvdi/overwritefs" is optionalOption(opts, validBoolean)
        }
      }
    }

    validator[ExternalVolume] { v =>
      v.external.name is notEmpty
      v.external.provider is equalTo(name)

      v.external.options.get(driverOption) as s"external/options($quotedDriverOption)" is definedAnd(validLabel)
      v.external.options as "external/options" is
        valid(conditional[Map[String, String]](_.get(driverOption).contains("rexray"))(validRexRayOptions))
    }
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  private[this] def matchesProvider(volume: ExternalVolume): Boolean = volume.external.provider == name

  private[this] def namesOfMatchingVolumes(app: AppDefinition): Iterable[String] =
    app.externalVolumes.filter(matchesProvider).map(_.external.name)

}
