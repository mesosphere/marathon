package mesosphere.marathon
package api.validation

import com.wix.accord.Validator
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.{UnitTest, ValidationTestLike}
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state._

class AppDefinitionValidationTest extends UnitTest with ValidationTestLike {
  import AppDefinition._

  "AppDefinition" when {
    "created with the default unreachable strategy" should {
      "be valid" in new Fixture {
        val app = AppDefinition(id = AbsolutePathId("/test"), cmd = Some("sleep 1000"), role = "*")
        validator(app) shouldBe aSuccess
      }
    }

    "created with dependencies" should {

      "be valid with dependencies pointing to a a subtree of this app" in new Fixture {
        val app = AppDefinition(
          id = AbsolutePathId("/a/b/c/d"),
          role = "*",
          cmd = Some("sleep 1000"),
          dependencies = Set(AbsolutePathId("/a/b/c/e"))
        )
        validator(app) shouldBe aSuccess
      }

      "be valid with dependencies pointing to a different subtree (Regression for #5024)" in new Fixture {
        val app = AppDefinition(
          id = AbsolutePathId("/a/b/c/d"),
          role = "*",
          cmd = Some("sleep 1000"),
          dependencies = Set(AbsolutePathId("/x/y/z"))
        )
        validator(app) shouldBe aSuccess
      }

      "be invalid with dependencies with invalid path chars" in new Fixture {
        val app = AppDefinition(
          id = AbsolutePathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(AbsolutePathId("/a/.../")),
          role = "*"
        )

        validator(app) should haveViolations(
          "/dependencies(0)" -> """must fully match regular expression '^(([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9])\.)*([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9])|(\.|\.\.)$'"""
        )
      }
    }

    "with residency" should {

      "be invalid if persistent volumes change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(container = Some(Container.Mesos(Seq(persistentVolume("foo", 2)))))
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations("/" -> "Persistent volumes can not be changed!")
      }

      "be invalid if ports change" in new Fixture {
        val app = validResidentApp
        val to1 = app.copy(portDefinitions = Seq.empty) // no port
        val to2 = app.copy(portDefinitions = Seq(PortDefinition(1))) // different port
        AppDefinition.residentUpdateIsValid(app)(to1) should haveViolations("/" -> PersistentVolumeHostPortsChanged)
        AppDefinition.residentUpdateIsValid(app)(to2) should haveViolations("/" -> PersistentVolumeHostPortsChanged)
      }

      "be invalid if cpu changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(cpus = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations("/cpus" -> PersistentVolumeResourcesChanged)
      }

      "be invalid if mem changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(mem = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations("/mem" -> PersistentVolumeResourcesChanged)
      }

      "be invalid if disk changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(disk = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations("/disk" -> PersistentVolumeResourcesChanged)
      }

      "be invalid if gpus change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(gpus = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations("/gpus" -> PersistentVolumeResourcesChanged)
      }

      "be invalid with default upgrade strategy" in new Fixture {
        val app = validResidentApp
        val to = app.copy(upgradeStrategy = UpgradeStrategy.empty)
        AppDefinition.residentUpdateIsValid(app)(to) should haveViolations(
          "/upgradeStrategy/maximumOverCapacity" -> "got 1.0, expected 0.0"
        )
      }
    }
  }

  class Fixture {
    implicit val validator: Validator[AppDefinition] =
      AppDefinition.validAppDefinition(Set.empty, ValidationHelper.roleSettings())(PluginManager.None)
    def validApp =
      AppDefinition(
        id = AbsolutePathId("/a/b/c/d"),
        role = "*",
        cmd = Some("sleep 1000"),
        portDefinitions = Seq(PortDefinition(0, name = Some("default")))
      )
    def persistentVolume(path: String, size: Long = 1) =
      VolumeWithMount(
        volume = PersistentVolume(name = None, persistent = PersistentVolumeInfo(size)),
        mount = VolumeMount(volumeName = None, mountPath = path, readOnly = false)
      )
    def validResidentApp =
      validApp.copy(
        container = Some(Container.Mesos(Seq(persistentVolume("foo")))),
        upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0, maximumOverCapacity = 0)
      )

  }
}
