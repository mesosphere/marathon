package mesosphere.marathon
package api.validation

import mesosphere.UnitTest
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state.{ AppDefinition, PathId }

class AppDefinitionValidationTest extends UnitTest {

  "AppDefinition" when {

    "created with dependencies" should {

      "be valid with dependencies pointing to a a subtree of this app" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/b/c/e"))
        )
        validator(app).isSuccess shouldBe true
      }

      "be valid with dependencies pointing to a different subtree (Regression for #5024)" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/x/y/z"))
        )
        validator(app).isSuccess shouldBe true
      }

      "be invalid with dependencies with invalid path chars" in new Fixture {
        val app = AppDefinition(
          id = PathId("/a/b/c/d"),
          cmd = Some("sleep 1000"),
          dependencies = Set(PathId("/a/.../"))
        )
        validator(app).isSuccess shouldBe false
      }
    }
  }

  class Fixture {
    val validator = AppDefinition.validAppDefinition(PluginManager.None)
  }
}
