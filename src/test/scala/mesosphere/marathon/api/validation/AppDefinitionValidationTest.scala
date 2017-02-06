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
          unreachableStrategy = UnreachableStrategy(0.second))

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
  }

  class Fixture {
    val validator = AppDefinition.validAppDefinition(Set.empty)(PluginManager.None)
  }
}
