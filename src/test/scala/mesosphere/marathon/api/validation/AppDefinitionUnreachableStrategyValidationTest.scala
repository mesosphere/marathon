package mesosphere.marathon
package api.validation

import mesosphere.UnitTest
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state.{ AppDefinition, PathId, UnreachableStrategy }
import com.wix.accord.scalatest.ResultMatchers

import scala.concurrent.duration._

class AppDefinitionValidationTest extends UnitTest with ResultMatchers {

  "AppDefinition" when {
    "created with the default unreachable strategy" should {
      "be valid" in {
        val f = new Fixture()
        val app = AppDefinition(id = PathId("/test"), cmd = Some("sleep 1000"))

        val validation = f.appDefinitionValidator(app)
        validation shouldBe aSuccess

      }
    }

    "created with an invalid unreachable strategy" should {
      "be invalid" in {
        val f = new Fixture()
        val app = AppDefinition(
          id = PathId("/test"),
          cmd = Some("sleep 1000"),
          unreachableStrategy = UnreachableStrategy(0.second))

        val expectedViolation = GroupViolationMatcher(description = "unreachableStrategy", constraint = "is invalid")
        f.appDefinitionValidator(app) should failWith(expectedViolation)
      }
    }
  }

  class Fixture {
    val appDefinitionValidator = AppDefinition.validAppDefinition(Set.empty)(PluginManager.None)
  }
}
