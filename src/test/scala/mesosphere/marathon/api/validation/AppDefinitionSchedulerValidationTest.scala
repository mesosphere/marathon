package mesosphere.marathon
package api.validation

import mesosphere.UnitTest
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.raml.Apps
import mesosphere.marathon.state._

class AppDefinitionSchedulerValidationTest extends UnitTest {

  private lazy val validAppDefinition = AppDefinition.validAppDefinition(Set())(PluginManager.None)

  class Fixture {
    def normalApp = AppDefinition(
      id = PathId("/test"),
      cmd = Some("sleep 1000"))

    def schedulerAppWithApi(
      frameworkName: String = "Framework-42",
      migrationApiVersion: String = "v1",
      migrationApiPath: String = "/v1/plan"): AppDefinition = {

      AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        instances = 1,
        upgradeStrategy = UpgradeStrategy(0, 0),
        labels = Map(
          Apps.LabelDcosPackageFrameworkName -> frameworkName,
          Apps.LabelDcosMigrationApiVersion -> migrationApiVersion,
          Apps.LabelDcosMigrationApiPath -> migrationApiPath
        )
      )
    }
  }
  "AppDefinitionSchedulerValidation" should {
    "scheduler app using API is considered a scheduler and valid" in {
      val f = new Fixture
      Given("an scheduler with required labels, 1 instance, correct upgradeStrategy and 1 readinessCheck")
      val app = f.schedulerAppWithApi()

      Then("the app is considered valid")
      validAppDefinition(app).isSuccess shouldBe true
    }

    "required scheduler labels" in {
      val f = new Fixture
      Given("an app with 1 instance and an UpgradeStrategy of (0,0)")
      val normalApp = f.normalApp.copy(
        instances = 1,
        upgradeStrategy = UpgradeStrategy(0, 0)
      )

      When("the Migration API version is added")
      val step1 = normalApp.copy(labels = normalApp.labels + (
        Apps.LabelDcosMigrationApiVersion -> "v1"
      ))
      Then("the app is not valid")
      validAppDefinition(step1).isSuccess shouldBe false

      When("the framework label is added")
      val step2 = normalApp.copy(labels = step1.labels + (
        Apps.LabelDcosPackageFrameworkName -> "Framework-42"
      ))
      Then("the app is not valid")
      validAppDefinition(step2).isSuccess shouldBe false

      When("the Migration API path is added")
      val step3 = normalApp.copy(labels = step2.labels + (
        Apps.LabelDcosMigrationApiPath -> "/v1/plan"
      ))
      Then("the app is valid")
      validAppDefinition(step3).isSuccess shouldBe true
    }

    "If a scheduler application defines DCOS_MIGRATION_API_VERSION, only 'v1' is valid" in {
      val f = new Fixture
      Given("a scheduler app defining DCOS_MIGRATION_API_VERSION other than 'v1'")
      val app = f.schedulerAppWithApi(migrationApiVersion = "v2")

      Then("the validation should fail")
      validAppDefinition(app).isFailure shouldBe true
    }

    "If a scheduler application defines DCOS_MIGRATION_API_PATH it must be non-empty" in {
      val f = new Fixture
      Given("a scheduler app with an empty migration path")
      val app = f.schedulerAppWithApi(migrationApiPath = "")

      Then("the validation should fail")
      validAppDefinition(app).isFailure shouldBe true
    }

    "If a scheduler application defines DCOS_PACKAGE_FRAMEWORK_NAME it must be non-empty" in {
      val f = new Fixture
      Given("a scheduler app with an empty framework name")
      val app = f.schedulerAppWithApi(frameworkName = "")

      Then("the validation should fail")
      validAppDefinition(app).isFailure shouldBe true
    }
  }
}
