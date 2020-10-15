package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.marathon.Builders.newAppDefinition.appIdIncrementor
import mesosphere.{UnitTest, ValidationTestLike}
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.{Builders, Features}
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, CSIExternalVolumeInfo, Container, VolumeWithMount}

class CSIProviderValidationsTest extends UnitTest with ValidationTestLike {
  def newCsiAppDef(
      id: AbsolutePathId = AbsolutePathId(s"/app-${appIdIncrementor.incrementAndGet()}"),
      name: String = "test",
      instances: Int = 1,
      mountReadonly: Boolean = false,
      accessMode: CSIExternalVolumeInfo.AccessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_MULTI_WRITER
  ) = {
    Builders.newAppDefinition(
      id = id,
      instances = instances,
      container = Some(
        Container.Mesos(
          volumes = Seq(
            VolumeWithMount(
              Builders.newExternalVolume
                .csi(Builders.newCSIExternalVolumeInfo(name = name, accessMode = accessMode)),
              Builders.newVolumeMount(readOnly = mountReadonly)
            )
          )
        )
      )
    )
  }

  val validator = AppDefinition.validAppDefinition(Set(Features.EXTERNAL_VOLUMES), ValidationHelper.roleSettings())(PluginManager.None)

  "allows app to have 0 instances for non-multi access modes" in {
    val app = newCsiAppDef(instances = 0, accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER)

    validator(app) shouldBe aSuccess
  }

  "prevent app from scaling up for non-multi access modes" in {
    val app = newCsiAppDef(instances = 2, accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER)

    validator(app) should haveViolations(
      "/instances" -> ("got 2, expected 1 or less " + CSIProviderValidations.WhileNonShareableCSIVolumesExistMessage)
    )
  }

  "prevent an app from being specified multiple times for non-multi access modes" in {
    val rootGroup = Builders.newRootGroup(apps =
      Seq(
        newCsiAppDef(id = AbsolutePathId("/app-1"), accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER, name = "my-vol"),
        newCsiAppDef(id = AbsolutePathId("/app-2"), accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER, name = "my-vol")
      )
    )

    val result = CSIProviderValidations.rootGroup(rootGroup)

    result should haveViolations(
      "/apps(0)/externalVolumes(0)" -> "Volume name 'my-vol' in /app-1 conflicts with volume(s) of same name in app(s): /app-2",
      "/apps(1)/externalVolumes(0)" -> "Volume name 'my-vol' in /app-2 conflicts with volume(s) of same name in app(s): /app-1"
    )
  }

  "prevent a CSI volume from having a RW volume mount mode if the app is read-only" in {
    val app = newCsiAppDef(mountReadonly = false, accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_READER_ONLY)

    validator(app) should haveViolations(
      "/container/volumes(0)/volume" -> CSIProviderValidations.CSIMountReadonlyForReadonlyAccessModeMessage
    )
  }

  "prevent a CSI volume from scaling if multi-reader-single-writer and mount mode RW" in {
    val scaledAppReadOnly =
      newCsiAppDef(instances = 2, accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER, mountReadonly = true)
    val scaledAppReadWrite =
      newCsiAppDef(instances = 2, accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER, mountReadonly = false)

    validator(scaledAppReadOnly) shouldBe aSuccess

    validator(scaledAppReadWrite) should haveViolations(
      "/instances" -> ("got 2, expected 1 or less " + CSIProviderValidations.WhileWritableSingleWriterVolumesExistMessage)
    )
  }

}
