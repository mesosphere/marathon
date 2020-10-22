package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.marathon.Builders.newAppDefinition.appIdIncrementor
import mesosphere.{UnitTest, ValidationTestLike}
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.{AllConf, Builders, Features, MarathonConf}
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, CSIExternalVolumeInfo, Container, RootGroup, VolumeWithMount}

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

  "root group validation" when {
    val conf = AllConf.withTestConfig("--enable_features", "external_volumes")
    val validator = RootGroup.validRootGroup(conf)

    "access mode is single writer" should {
      "prevent a volume from being specified multiple times" in {
        val rootGroup = Builders.newRootGroup(apps =
          Seq(
            newCsiAppDef(id = AbsolutePathId("/app-1"), accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER, name = "my-vol"),
            newCsiAppDef(id = AbsolutePathId("/app-2"), accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER, name = "my-vol")
          )
        )

        val result = validator(rootGroup)

        result should haveViolations(
          "/apps(0)/container" -> "Volume name 'my-vol' in /app-1 conflicts with volume(s) of same name in app(s): /app-2",
          "/apps(1)/container" -> "Volume name 'my-vol' in /app-2 conflicts with volume(s) of same name in app(s): /app-1"
        )
      }

    }
    "access mode is multi reader" should {
      "allow multiple apps to read the same volume" in {
        val ro1 = newCsiAppDef(
          AbsolutePathId("/app1"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_READER_ONLY,
          mountReadonly = true
        )
        val ro2 = newCsiAppDef(
          AbsolutePathId("/app2"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_READER_ONLY,
          mountReadonly = true
        )

        val rg = Builders.newRootGroup(apps = Seq(ro1, ro2))

        validator(rg) shouldBe aSuccess
      }
    }

    "access mode is multi reader single writer" should {
      "allow multiple apps to read the same volume, and one to write" in {
        val ro1 = newCsiAppDef(
          AbsolutePathId("/ro1"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER,
          mountReadonly = true
        )
        val ro2 = newCsiAppDef(
          AbsolutePathId("/ro2"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER,
          mountReadonly = true
        )
        val rw3 = newCsiAppDef(
          AbsolutePathId("/rw3"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER,
          mountReadonly = false
        )

        val rg = Builders.newRootGroup(apps = Seq(ro1, ro2, rw3))

        validator(rg) shouldBe aSuccess
      }

      "prevent multiple apps to write the same volume" in {
        val rw1 = newCsiAppDef(
          AbsolutePathId("/rw1"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER,
          mountReadonly = false
        )
        val rw2 = newCsiAppDef(
          AbsolutePathId("/rw2"),
          name = "multi-reader",
          accessMode = CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER,
          mountReadonly = false
        )

        val rg = Builders.newRootGroup(apps = Seq(rw1, rw2))

        validator(rg) should haveViolations(
          "/apps(0)/container" -> "Volume name 'multi-reader' in /rw1 conflicts with volume(s) of same name in app(s): /rw2"
        )
      }
    }
  }
}
