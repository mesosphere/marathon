package mesosphere.marathon.upgrade

import mesosphere.marathon.state.{ AppDefinition, PathId, Residency, UpgradeStrategy }
import mesosphere.marathon.test.Mockito
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

class RestartStrategyTest extends FunSuite with Matchers with GivenWhenThen with Mockito {

  import mesosphere.marathon.upgrade.TaskReplaceActor._

  test("strategy for resident app with 1 instance") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 1,
      upgradeStrategy = UpgradeStrategy.forResidentTasks, // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 1

    And("we kill one task")
    strategy.nrToKillImmediately shouldBe 1
  }

  test("strategy for starting resident app with 1 instance and no tasks running") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 1,
      upgradeStrategy = UpgradeStrategy.forResidentTasks, // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = 0)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 1

    And("we kill no task")
    strategy.nrToKillImmediately shouldBe 0
  }

  test("strategy for resident app with 5 instances") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 5,
      upgradeStrategy = UpgradeStrategy.forResidentTasks, // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 5

    And("we kill two tasks")
    strategy.nrToKillImmediately shouldBe 2
  }

  test("strategy for resident app with 5 instances, already over capacity, maxHealthCapacity = 1") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 5,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1, maximumOverCapacity = 0), // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances + 2)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 5

    And("we kill three tasks")
    strategy.nrToKillImmediately shouldBe 3
  }

  test("strategy for resident app with 5 instances, already under capacity, maxHealthCapacity = 1") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 5,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1, maximumOverCapacity = 0), // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances - 2)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 5

    And("we kill no tasks")
    strategy.nrToKillImmediately shouldBe 0
  }

  test("strategy for resident app with 5 instances, already at capacity, maxHealthCapacity = 1") {
    Given("A resident app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 5,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1, maximumOverCapacity = 0), // UpgradeStrategy(0.5, 0)
      residency = Some(Residency.default))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances)

    Then("the app instance count is not exceeded")
    strategy.maxCapacity shouldBe 5

    And("we kill one task")
    strategy.nrToKillImmediately shouldBe 1
  }

  test("strategy for normal app with 1 instance") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 1,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.5, maximumOverCapacity = 0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances)

    Then("the app instance count is exceeded by one")
    strategy.maxCapacity shouldBe 2

    And("we kill 0 tasks immediately")
    strategy.nrToKillImmediately shouldBe 0
  }

  test("strategy for normal app with 1 instance and no tasks running") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 1,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.5, maximumOverCapacity = 0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = 0)

    Then("the app instance count does not need to be exceeded (since we can start a task without kills)")
    strategy.maxCapacity shouldBe 1

    And("we kill no tasks immediately")
    strategy.nrToKillImmediately shouldBe 0
  }

  test("maxCapacity strategy for normal app is not exceeded when a task can be killed immediately") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 2,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.5, maximumOverCapacity = 0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = app.instances)

    Then("the app instance count is exceeded by one")
    strategy.maxCapacity shouldBe 2

    And("we kill one task immediately")
    strategy.nrToKillImmediately shouldBe 1
  }

  test("less running tasks than actual instance count and less than min health capacity") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 10,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.1, maximumOverCapacity = 0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = 1)

    Then("the maxCapacity equals the app.instance count")
    strategy.maxCapacity shouldBe 10

    And("no tasks are killed immediately")
    strategy.nrToKillImmediately shouldBe 0
  }

  test("max over capacity 1") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 10,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.1, maximumOverCapacity = 1.0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = 10)

    Then("the maxCapacity is double the app.instance count")
    strategy.maxCapacity shouldBe 20

    And("9 tasks are killed immediately")
    strategy.nrToKillImmediately shouldBe 9
  }

  test("maxOverCapacity 1 and minHealth 1") {
    Given("A normal app")
    val app = AppDefinition(
      id = PathId("/app"),
      instances = 10,
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1, maximumOverCapacity = 1.0))

    When("the ignition strategy is computed")
    val strategy = computeRestartStrategy(app, runningTasksCount = 10)

    Then("the maxCapacity is double the app.instance count")
    strategy.maxCapacity shouldBe 20

    And("no tasks are killed immediately")
    strategy.nrToKillImmediately shouldBe 0
  }

}
