package mesosphere.marathon
package api.validation

import mesosphere.UnitTest
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state._
import com.wix.accord.scalatest.ResultMatchers
import org.apache.mesos.{ Protos => Mesos }

import scala.concurrent.duration._

class AppDefinitionValidationTest extends UnitTest with ResultMatchers {

  "AppDefinition" when {
    "created with the default unreachable strategy" should {
      "be valid" in new Fixture {
        val app = AppDefinition(id = PathId("/test"), cmd = Some("sleep 1000"))

        val validation = validator(app)
        validation shouldBe aSuccess

      }
    }

    "created with an invalid unreachable strategy" should {
      "be invalid" in new Fixture {
        val app = AppDefinition(
          id = PathId("/test"),
          cmd = Some("sleep 1000"),
          unreachableStrategy = UnreachableEnabled(0.seconds))

        val expectedViolation = GroupViolationMatcher(description = "unreachableStrategy", constraint = "is invalid")
        validator(app) should failWith(expectedViolation)
      }
    }

    "created with dependencies" should {

      "be valid with dependencies pointing to a a subtree of this app" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/b/c/e"))
        )
        validator(app) shouldBe aSuccess
      }

      "be valid with dependencies pointing to a different subtree (Regression for #5024)" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/x/y/z"))
        )
        validator(app) shouldBe aSuccess
      }

      "be invalid with dependencies with invalid path chars" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/.../"))
        )
        val expectedViolation = GroupViolationMatcher(description = "dependencies", constraint = "is invalid")
        validator(app) should failWith(expectedViolation)
      }
    }

    "with residency" should {

      "be invalid if persistent volumes change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(container = Some(Container.Mesos(Seq(persistentVolume("foo", 2)))))
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("value" -> "Persistent volumes can not be changed!")
      }

      "be invalid if ports change" in new Fixture {
        val app = validResidentApp
        val to1 = app.copy(portDefinitions = Seq.empty) // no port
        val to2 = app.copy(portDefinitions = Seq(PortDefinition(1))) // different port
        AppDefinition.residentUpdateIsValid(app)(to1) should failWith("value" -> "Resident Tasks may not change resource requirements!")
        AppDefinition.residentUpdateIsValid(app)(to2) should failWith("value" -> "Resident Tasks may not change resource requirements!")
      }

      "be invalid if cpu changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(cpus = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("value" -> "Resident Tasks may not change resource requirements!")
      }

      "be invalid if mem changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(mem = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("value" -> "Resident Tasks may not change resource requirements!")
      }

      "be invalid if disk changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(disk = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("value" -> "Resident Tasks may not change resource requirements!")
      }

      "be invalid if gpus change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(gpus = 3))
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("value" -> "Resident Tasks may not change resource requirements!")
      }

      "be invalid with default upgrade strategy" in new Fixture {
        val app = validResidentApp
        val to = app.copy(upgradeStrategy = UpgradeStrategy.empty)
        AppDefinition.residentUpdateIsValid(app)(to) should failWith("upgradeStrategy" -> "got 1.0, expected 0.0")
      }

    }
  }

  class Fixture {
    val validator = AppDefinition.validAppDefinition(Set.empty)(PluginManager.None)
    def validApp = AppDefinition(
      id = PathId("/a/b/c/d"),
      cmd = Some("sleep 1000"),
      portDefinitions = Seq(PortDefinition(0, name = Some("default")))
    )
    def persistentVolume(path: String, size: Long = 1) = PersistentVolume(path, PersistentVolumeInfo(size), Mesos.Volume.Mode.RW)
    def validResidentApp = validApp.copy(
      container = Some(Container.Mesos(Seq(persistentVolume("foo")))),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0, maximumOverCapacity = 0)
    )

  }
}
