package mesosphere.marathon.api.validation

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.scalatest.{ GivenWhenThen, Matchers }

class AppDefinitionSchedulerValidationTest extends MarathonSpec with Matchers with GivenWhenThen {

  test("scheduler app using API is considered a scheduler and valid") {
    val f = new Fixture
    Given("an scheduler with required labels, 1 instance, correct upgradeStrategy and 1 readinessCheck")
    val app = f.schedulerAppWithApi()

    Then("the app is considered valid")
    AppDefinition.validAppDefinition(app).isSuccess shouldBe true
  }

  test("required scheduler labels") {
    val f = new Fixture
    Given("an app with 1 instance and an UpgradeStrategy of (0,0)")
    val normalApp = f.normalApp.copy(
      instances = 1,
      upgradeStrategy = UpgradeStrategy(0, 0)
    )

    When("the Migration API version is added")
    val step1 = normalApp.copy(labels = normalApp.labels + (
      AppDefinition.Labels.DcosMigrationApiVersion -> "v1"
    ))
    Then("the app is not valid")
    AppDefinition.validAppDefinition(step1).isSuccess shouldBe false

    When("the framework label is added")
    val step2 = normalApp.copy(labels = step1.labels + (
      AppDefinition.Labels.DcosPackageFrameworkName -> "Framework-42"
    ))
    Then("the app is not valid")
    AppDefinition.validAppDefinition(step2).isSuccess shouldBe false

    When("the Migration API path is added")
    val step3 = normalApp.copy(labels = step2.labels + (
      AppDefinition.Labels.DcosMigrationApiPath -> "/v1/plan"
    ))
    Then("the app is valid")
    AppDefinition.validAppDefinition(step3).isSuccess shouldBe true
  }

  test("If a scheduler application defines DCOS_MIGRATION_API_VERSION, only 'v1' is valid") {
    val f = new Fixture
    Given("a scheduler app defining DCOS_MIGRATION_API_VERSION other than 'v1'")
    val app = f.schedulerAppWithApi(migrationApiVersion = "v2")

    Then("the validation should fail")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  test("If a scheduler application defines DCOS_MIGRATION_API_PATH it must be non-empty") {
    val f = new Fixture
    Given("a scheduler app with an empty migration path")
    val app = f.schedulerAppWithApi(migrationApiPath = "")

    Then("the validation should fail")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  test("If a scheduler application defines DCOS_PACKAGE_FRAMEWORK_NAME it must be non-empty") {
    val f = new Fixture
    Given("a scheduler app with an empty framework name")
    val app = f.schedulerAppWithApi(frameworkName = "")

    Then("the validation should fail")
    AppDefinition.validAppDefinition(app).isFailure shouldBe true
  }

  class Fixture {
    def normalApp = AppDefinition(
      cmd = Some("sleep 1000"))

    def schedulerAppWithApi(
      frameworkName: String = "Framework-42",
      migrationApiVersion: String = "v1",
      migrationApiPath: String = "/v1/plan"): AppDefinition = {

      AppDefinition(
        cmd = Some("sleep 1000"),
        instances = 1,
        upgradeStrategy = UpgradeStrategy(0, 0),
        labels = Map(
          AppDefinition.Labels.DcosPackageFrameworkName -> frameworkName,
          AppDefinition.Labels.DcosMigrationApiVersion -> migrationApiVersion,
          AppDefinition.Labels.DcosMigrationApiPath -> migrationApiPath
        )
      )
    }
  }

}
