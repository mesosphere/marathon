package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.validation.SchedulingValidation
import mesosphere.marathon.core.externalvolume.ExternalVolumeRamlHelpers
import mesosphere.marathon.core.externalvolume.impl.providers.OptionSupport._
import mesosphere.marathon.core.externalvolume.impl.{ExternalVolumeProvider, ExternalVolumeValidations}
import mesosphere.marathon.raml.{App, AppExternalVolume, EngineType, ReadMode, Container => AppContainer}
import mesosphere.marathon.state._

import scala.jdk.CollectionConverters._
import org.apache.mesos.Protos.{Parameter, Parameters, Volume => MesosVolume}
import org.apache.zookeeper.KeeperException.BadArgumentsException

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles external volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
private[externalvolume] case object DVDIProvider extends ExternalVolumeProvider {
  override val name: String = "dvdi"

  override def validations: ExternalVolumeValidations = DVDIProviderValidations

  object Builders {
    def dockerVolumeParameters(volume: GenericExternalVolumeInfo): Seq[Parameter] = {
      import OptionLabelPatterns._
      val prefix: String = name + OptionNamespaceSeparator
      // don't let the user override these
      val ignore = Set(driverOption)
      // external.size trumps any user-specified dvdi/size option
      val opts = volume.options ++ Map[String, String](
        volume.size.map(prefix + "size" -> _.toString).toList: _*
      )

      // forward all dvdi/* options to the dvdcli driver, stripping the dvdi/ prefix
      // and trimming the values
      opts.filterKeys { k =>
        k.startsWith(prefix) && !ignore.contains(k.toLowerCase)
      }.iterator.map {
        case (k, v) =>
          Parameter.newBuilder
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

    def toUnifiedContainerVolume(volume: ExternalVolume, mount: VolumeMount): MesosVolume = {
      volume.external match {
        case info: CSIExternalVolumeInfo =>
          throw new IllegalStateException("Bug: CSIProvider should be used for CSIExternalVolumeInfo")
        case info: GenericExternalVolumeInfo =>
          val driverName = info.options(driverOption)
          val volBuilder = MesosVolume.Source.DockerVolume.newBuilder
            .setDriver(driverName)
            .setName(info.name)

          // these parameters are only really used for the mesos containerizer, not the docker
          // containerizer. the docker containerizer simply ignores them.
          applyOptions(volBuilder, dockerVolumeParameters(info))

          val mode = VolumeMount.readOnlyToProto(mount.readOnly)
          MesosVolume.newBuilder
            .setContainerPath(mount.mountPath)
            .setMode(mode)
            .setSource(
              MesosVolume.Source.newBuilder
                .setType(MesosVolume.Source.Type.DOCKER_VOLUME)
                .setDockerVolume(volBuilder.build)
            )
            .build
      }
    }
  } // Builders

  override def build(ev: ExternalVolume, mount: VolumeMount): MesosVolume =
    Builders.toUnifiedContainerVolume(ev, mount)

  val driverOption = "dvdi/driver"
  val quotedDriverOption = '"' + driverOption + '"'

  val driverValueRexRay = "rexray"

}

private[impl] object DVDIProviderValidations extends ExternalVolumeValidations {

  import DVDIProvider._
  import mesosphere.marathon.api.v2.Validation._

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  override lazy val rootGroup = ValidationHelpers.validateUniqueVolumes(name)

  override lazy val ramlApp = {
    val haveOnlyOneInstance: Validator[App] =
      isTrue[App]((app: App) => s"Number of instances is limited to 1 when declaring DVDI volumes in app [$app.id]") {
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
        ValidationHelpers
          .namesOfMatchingVolumes(name, app)
          .groupBy(identity)
          .iterator
          .map { case (name, names) => name -> names.size }
          .toMap
    }

    val validContainer = {
      import PathPatterns._

      val validMesosVolume = validator[AppExternalVolume] { volume =>
        volume.mode is equalTo(ReadMode.Rw)
        volume.containerPath is notOneOf(DotPaths: _*)
      }

      val validDockerExternalVolumeInfo = validator[raml.GenericExternalVolumeInfo] { external =>
        external.options is isTrue(s"must only contain $driverOption")(_.view.filterKeys(_ != driverOption).isEmpty)
        external.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
      }

      val validDockerExternalVolume: Validator[raml.ExternalVolumeInfo] = {
        case _: raml.CSIExternalVolumeInfo =>
          throw new IllegalStateException("This validator should only be applied to DVDI volumes")
        case dvdi: raml.GenericExternalVolumeInfo =>
          validDockerExternalVolumeInfo(dvdi)
      }

      val validDockerVolume = validator[AppExternalVolume] { volume =>
        volume.external is validDockerExternalVolume
        volume.containerPath is notOneOf(DotPaths: _*)
      }

      def ifDVDIVolume(vtor: Validator[AppExternalVolume]): Validator[AppExternalVolume] =
        conditional(ValidationHelpers.matchesProviderRaml(name, _))(vtor)

      def volumeValidator(container: EngineType): Validator[AppExternalVolume] =
        container match {
          case EngineType.Mesos => validMesosVolume
          case EngineType.Docker => validDockerVolume
        }

      validator[AppContainer] { ct =>
        ct.volumes.collect { case v: AppExternalVolume => v } as "volumes" is
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
      isTrue[AppDefinition]((app: AppDefinition) => s"Number of instances is limited to 1 when declaring DVDI volumes in app [$app.id]") {
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
        ValidationHelpers
          .namesOfMatchingVolumes(name, app)
          .groupBy(identity)
          .iterator
          .map { case (name, names) => name -> names.size }
          .toMap
    }

    val validContainer = {
      import PathPatterns._

      val validMesosVolume = validator[ExternalVolume] { volume =>
        volume.name is optional(notEmpty)
      }

      val validMesosVolumeMount = validator[VolumeMount] { mount =>
        mount.readOnly is false
        mount.mountPath is notOneOf(DotPaths: _*)
      }

      val validGenericExternalVolumeInfo = validator[GenericExternalVolumeInfo] { external =>
        external.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
        external.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
      }

      val validCSIExternalVolumeInfo = validator[CSIExternalVolumeInfo] { external => }
      val validExternalVolumeInfo: Validator[ExternalVolumeInfo] = {
        case volume: GenericExternalVolumeInfo => validGenericExternalVolumeInfo(volume)
      }

      val validExternalVolume = validator[ExternalVolume] { volume =>
        volume.external is validExternalVolumeInfo
      }

      val validDockerVolumeMount = validator[VolumeMount] { mount =>
        mount.mountPath is notOneOf(DotPaths: _*)
      }

      def volumeValidator(container: Container) =
        container match {
          case _: Container.Mesos => validMesosVolume
          case _: Container.MesosDocker => validMesosVolume
          case _: Container.Docker => validExternalVolume
        }

      def volumeMountValidator(container: Container) =
        container match {
          case _: Container.Docker => validDockerVolumeMount
          case _ => validMesosVolumeMount
        }

      validator[Container] { ct =>
        ct.volumes.collect {
          case VolumeWithMount(ev: ExternalVolume, _) if ValidationHelpers.matchesProvider(name, ev) => ev
        } as "volumes" is every(volumeValidator(ct))
        ct.volumes.collect {
          case VolumeWithMount(ev: ExternalVolume, mount) if ValidationHelpers.matchesProvider(name, ev) => mount
        } as "mounts" is every(volumeMountValidator(ct))
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

    val validateGenericExternalVolumeInfo = validator[GenericExternalVolumeInfo] { external =>
      external.provider is equalTo(name)

      external.options.get(driverOption) as s""""external/options($quotedDriverOption)"""" is
        definedAnd(validLabel)

      external.options as "external/options" is
        conditional[Map[String, String]](_.get(driverOption).contains(driverValueRexRay))(validRexRayOptions)
    }

    val validateExternalVolumeInfo: Validator[ExternalVolumeInfo] = {
      case csi: CSIExternalVolumeInfo =>
        ???
      case genericExternalVolumeInfo: GenericExternalVolumeInfo =>
        validateGenericExternalVolumeInfo(genericExternalVolumeInfo)
    }

    validator[ExternalVolume] { v =>
      v.external.name is notEmpty
      v.external is validateExternalVolumeInfo
    }
  }

  override def ramlVolume(container: raml.Container) = {
    import PathPatterns._
    import VolumeOptions._

    val validMesosVolume = validator[AppExternalVolume] { volume =>
      volume.mode is equalTo(ReadMode.Rw)
      volume.containerPath is notOneOf(DotPaths: _*)
    }
    val dockerGenericVolumeInfo = validator[raml.GenericExternalVolumeInfo] { v =>
      v.options is isTrue(s"must only contain $driverOption")(_.filterKeys(_ != driverOption).isEmpty)
      v.size is isTrue("must be undefined for Docker containers")(_.isEmpty)
    }
    val dockerVolumeInfo: Validator[raml.ExternalVolumeInfo] = {
      case v: raml.GenericExternalVolumeInfo => dockerGenericVolumeInfo(v)
      case v: raml.CSIExternalVolumeInfo => ???
    }

    val validDockerVolume = validator[AppExternalVolume] { volume =>
      volume.containerPath is notOneOf(DotPaths: _*)
      volume.external is dockerVolumeInfo
    }

    val genericVolumeInfo = validator[raml.GenericExternalVolumeInfo] { v =>
      v.name is definedAnd(notEmpty)
      v.provider is definedAnd(equalTo(name))
      v.options.get(driverOption) as s"options($quotedDriverOption)" is definedAnd(validLabel)
      v.options is conditional[Map[String, String]](_.get(driverOption).contains(driverValueRexRay))(validRexRayOptions)
    }

    val volumeInfo: Validator[raml.ExternalVolumeInfo] = {
      case v: raml.GenericExternalVolumeInfo =>
        genericVolumeInfo(v)
      case v: raml.CSIExternalVolumeInfo =>
        ???
    }

    forAll(
      validator[AppExternalVolume] { v =>
        v.external is valid(valid(volumeInfo))
      },
      implied(container.`type` == EngineType.Mesos)(validMesosVolume),
      implied(container.`type` == EngineType.Docker)(validDockerVolume)
    )
  }
}
