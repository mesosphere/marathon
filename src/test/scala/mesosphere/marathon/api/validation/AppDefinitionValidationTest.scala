package mesosphere.marathon
package api.validation

import com.wix.accord.Validator
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => Mesos }

class AppDefinitionValidationTest extends UnitTest with ValidationTestLike {

  "AppDefinition" when {
    "created with the default unreachable strategy" should {
      "be valid" in new Fixture {
        val app = AppDefinition(id = PathId("/test"), cmd = Some("sleep 1000"))
        shouldSucceed(app)
      }
    }

    "created with dependencies" should {

      "be valid with dependencies pointing to a a subtree of this app" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/b/c/e"))
        )
        shouldSucceed(app)
      }

      "be valid with dependencies pointing to a different subtree (Regression for #5024)" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/x/y/z"))
        )
        shouldSucceed(app)
      }

      "be invalid with dependencies with invalid path chars" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/.../"))
        )

        shouldViolate(app, "/dependencies(0)" -> """must fully match regular expression '^(([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9])\.)*([a-z0-9]|[a-z0-9][a-z0-9\-]*[a-z0-9])|(\.|\.\.)$'""")
      }
    }

    "with residency" should {

      "be invalid if persistent volumes change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(container = Some(Container.Mesos(Seq(persistentVolume("foo", 2)))))
        shouldViolate(to, "/" -> "Persistent volumes can not be changed!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid if ports change" in new Fixture {
        val app = validResidentApp
        val to1 = app.copy(portDefinitions = Seq.empty) // no port
        val to2 = app.copy(portDefinitions = Seq(PortDefinition(1))) // different port
        shouldViolate(to1, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
        shouldViolate(to2, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid if cpu changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(cpus = 3))
        shouldViolate(to, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid if mem changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(mem = 3))
        shouldViolate(to, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid if disk changes" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(disk = 3))
        shouldViolate(to, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid if gpus change" in new Fixture {
        val app = validResidentApp
        val to = app.copy(resources = app.resources.copy(gpus = 3))
        shouldViolate(to, "/" -> "Resident Tasks may not change resource requirements!")(AppDefinition.residentUpdateIsValid(app))
      }

      "be invalid with default upgrade strategy" in new Fixture {
        val app = validResidentApp
        val to = app.copy(upgradeStrategy = UpgradeStrategy.empty)
        shouldViolate(to, "/upgradeStrategy/maximumOverCapacity" -> "got 1.0, expected 0.0")(AppDefinition.residentUpdateIsValid(app))
      }
    }
  }

  class Fixture {
    implicit val validator: Validator[AppDefinition] = AppDefinition.validAppDefinition(Set.empty)(PluginManager.None)
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
