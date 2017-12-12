package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.validation.SchedulingValidation
import mesosphere.marathon.core.externalvolume.impl.providers.OptionSupport._
import mesosphere.marathon.core.externalvolume.impl.{ ExternalVolumeProvider, ExternalVolumeValidations }
import mesosphere.marathon.raml.{ App, AppExternalVolume, EngineType, ReadMode, Container => AppContainer }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.Protos.{ ContainerInfo, Parameter, Parameters, Volume => MesosVolume }

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
      }(collection.breakOut)
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

    def toUnifiedContainerVolume(volume: ExternalVolume): MesosVolume = {
      val driverName = volume.external.options(driverOption)
      val volBuilder = MesosVolume.Source.DockerVolume.newBuilder
        .setDriver(driverName)
        .setName(volume.external.name)

      // these parameters are only really used for the mesos containerizer, not the docker
      // containerizer. the docker containerizer simply ignores them.
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

  override def build(builder: ContainerInfo.Builder, ev: ExternalVolume): Unit =
    builder.addVolumes(Builders.toUnifiedContainerVolume(ev))

  val driverOption = "dvdi/driver"
  val quotedDriverOption = '"' + driverOption + '"'
}

private[impl] object DVDIProviderValidations extends ExternalVolumeValidations {
  import DVDIProvider._
  import mesosphere.marathon.api.v2.Validation._

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  override lazy val rootGroup = new Validator[RootGroup] {
    override def apply(rootGroup: RootGroup): Result = {
      val appsByVolume: Map[String, Iterable[PathId]] =
        rootGroup.transitiveApps
          .flatMap { app => namesOfMatchingVolumes(app).map(_ -> app.id) }
          .groupBy { case (volumeName, _) => volumeName }
          .map { case (volumeName, volumes) => volumeName -> volumes.map { case (_, appId) => appId } }

      val appValid: Validator[AppDefinition] = {
        def volumeNameUnique(appId: PathId): Validator[ExternalVolume] = {
          def conflictingApps(vol: ExternalVolume): Iterable[PathId] =
            appsByVolume.getOrElse(vol.external.name, Iterable.empty).filter(_ != appId)

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
        group.apps.values as "apps" is every(appValid)
        group.groupsById.values as "groups" is every(groupValid)
      }

      // We need to call the validators recursively such that the "description" of the rule violations
      // is correctly calculated.
      groupValid(rootGroup)
    }

  }

  override lazy val ramlApp = {
    val haveOnlyOneInstance: Validator[App] =
      isTrue[App](
        (app: App) => s"Number of instances is limited to 1 when declaring DVDI volumes in app [$app.id]"
      ) {
          _.instances <= 1
        }

    case object haveUniqueExternalVolumeNames extends Validator[App] {
      override def apply(app: App): Result = {
        val conflicts = volumeNameCounts(app).filter { case (volumeName, number) => number > 1 }.keys
        group(
          conflicts.toSet[String].map { e =>
            RuleViolation(app.id, s"Requested DVDI volume '$e' is declared more than once within app ${app.id}")
          }
        )
      }

      /** @return a count of volume references-by-name within an app spec */
      def volumeNameCounts(app: App): Map[String, Int] =
        namesOfMatchingVolumes(app).groupBy(identity).map { case (name, names) => name -> names.size }(collection.breakOut)
    }

    val validContainer = {
      import PathPatterns._

      val validMesosVolume = validator[AppExternalVolume] {
        volume =>
          volume.mode is equalTo(ReadMode.Rw)
          volume.containerPath is notOneOf(DotPaths: _*)
      }

      val validDockerExternalVolume = validator[raml.ExternalVolume] { external =>
        external.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
        external.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
      }

      val validDockerVolume = validator[AppExternalVolume] { volume =>
        volume.external is validDockerExternalVolume
        volume.containerPath is notOneOf(DotPaths: _*)
      }

      def ifDVDIVolume(vtor: Validator[AppExternalVolume]): Validator[AppExternalVolume] = conditional(matchesProviderRaml)(vtor)

      def volumeValidator(container: EngineType): Validator[AppExternalVolume] = container match {
        case EngineType.Mesos => validMesosVolume
        case EngineType.Docker => validDockerVolume
      }

      validator[AppContainer] { ct =>
        ct.volumes.collect{ case v: AppExternalVolume => v } as "volumes" is
          every(ifDVDIVolume(volumeValidator(ct.`type`)))
      }
    }

    validator[App] { app =>
      app should haveUniqueExternalVolumeNames
      app should haveOnlyOneInstance
      app.container is optional(validContainer)
      app.upgradeStrategy is optional(SchedulingValidation.validForResidentTasks)
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
            RuleViolation(app.id, s"Requested DVDI volume '$e' is declared more than once within app ${app.id}")
          }
        )
      }

      /** @return a count of volume references-by-name within an app spec */
      def volumeNameCounts(app: AppDefinition): Map[String, Int] =
        namesOfMatchingVolumes(app).groupBy(identity).map { case (name, names) => name -> names.size }(collection.breakOut)
    }

    val validContainer = {
      import PathPatterns._

      val validMesosVolume = validator[ExternalVolume] {
        volume =>
          volume.mode is equalTo(Mode.RW)
          volume.containerPath is notOneOf(DotPaths: _*)
      }

      val validDockerVolume = validator[ExternalVolume] { volume =>
        volume.external.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
        volume.external.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
        volume.containerPath is notOneOf(DotPaths: _*)
      }

      def ifDVDIVolume(vtor: Validator[ExternalVolume]): Validator[ExternalVolume] = conditional(matchesProvider)(vtor)

      def volumeValidator(container: Container) = container match {
        case _: Container.Mesos => validMesosVolume
        case _: Container.MesosDocker => validMesosVolume
        case _: Container.MesosAppC => validMesosVolume
        case _: Container.Docker => validDockerVolume
      }

      validator[Container] { ct =>
        ct.volumes.collect { case ev: ExternalVolume => ev } as "volumes" is
          every(ifDVDIVolume(volumeValidator(ct)))
      }
    }

    validator[AppDefinition] { app =>
      app should haveUniqueExternalVolumeNames
      app should haveOnlyOneInstance
      app.container is optional(validContainer)
      app.upgradeStrategy is UpgradeStrategy.validForResidentTasks
    }
  }

  object VolumeOptions {

    val validRexRayOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opts =>
      opts.get("dvdi/volumetype") as "\"dvdi/volumetype\"" is optional(validLabel)
      opts.get("dvdi/newfstype") as "\"dvdi/newfstype\"" is optional(validLabel)
      opts.get("dvdi/iops") as "\"dvdi/iops\"" is optional(validNaturalNumber)
      opts.get("dvdi/overwritefs") as "\"dvdi/overwritefs\"" is optional(validBoolean)
    }
  }

  override lazy val volume = {
    import VolumeOptions._
    validator[ExternalVolume] { v =>
      v.external.name is notEmpty
      v.external.provider is equalTo(name)

      v.external.options.get(driverOption) as s""""external/options($quotedDriverOption)"""" is definedAnd(validLabel)
      v.external.options as "external/options" is
        conditional[Map[String, String]](_.get(driverOption).contains("rexray"))(validRexRayOptions)
    }
  }

  override def ramlVolume(container: raml.Container) = {
    import PathPatterns._
    import VolumeOptions._

    val validMesosVolume = validator[AppExternalVolume] {
      volume =>
        volume.mode is equalTo(ReadMode.Rw)
        volume.containerPath is notOneOf(DotPaths: _*)
    }
    val dockerVolumeInfo = validator[raml.ExternalVolume] { v =>
      v.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
      v.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
    }
    val validDockerVolume = validator[AppExternalVolume] { volume =>
      volume.containerPath is notOneOf(DotPaths: _*)
      volume.external is dockerVolumeInfo
    }
    val volumeInfo = validator[raml.ExternalVolume] { v =>
      v.name is definedAnd(notEmpty)
      v.provider is definedAnd(equalTo(name))
      v.options.get(driverOption) as s"options($quotedDriverOption)" is definedAnd(validLabel)
      v.options is conditional[Map[String, String]](_.get(driverOption).contains("rexray"))(validRexRayOptions)
    }
    forAll(
      validator[AppExternalVolume] { v =>
        v.external is valid(valid(volumeInfo))
      },
      implied(container.`type` == EngineType.Mesos)(validMesosVolume),
      implied(container.`type` == EngineType.Docker)(validDockerVolume)
    )
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  private[this] def matchesProvider(volume: ExternalVolume): Boolean = volume.external.provider == name
  private[this] def matchesProviderRaml(volume: AppExternalVolume): Boolean = volume.external.provider.contains(name)

  private[this] def namesOfMatchingVolumes(app: AppDefinition): Seq[String] =
    app.externalVolumes.withFilter(matchesProvider).map(_.external.name)

  private[this] def namesOfMatchingVolumes(app: App): Seq[String] =
    app.container.fold(Seq.empty[AppExternalVolume])(_.volumes.collect{ case v: AppExternalVolume => v })
      .withFilter(matchesProviderRaml).flatMap(_.external.name)
}
