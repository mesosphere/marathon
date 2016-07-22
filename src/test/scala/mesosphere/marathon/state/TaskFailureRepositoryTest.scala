package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.storage.repository.impl.legacy.TaskFailureEntityRepository
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{ InMemoryStore, MarathonStore }
import mesosphere.marathon.metrics.Metrics
import org.scalatest.{ GivenWhenThen, Matchers }

class TaskFailureRepositoryTest extends MarathonSpec with GivenWhenThen with Matchers {
  import TaskFailureTestHelper.taskFailure
  import mesosphere.FutureTestSupport._

  test("store and fetch") {
    Given("an empty taskRepository")
    val f = new Fixture

    When("we store a taskFailure")
    f.taskFailureRepo.store(taskFailure).futureValue

    And("fetch it")
    val readFailure = f.taskFailureRepo.get(PathId("/some/app")).futureValue

    Then("the resulting failure is the one we stored")
    readFailure should be(Some(taskFailure))
  }

  test("the last store wins") {
    Given("an empty taskRepository")
    val f = new Fixture

    When("we store a taskFailure")
    f.taskFailureRepo.store(taskFailure).futureValue

    And("another one for the same app")
    val anotherTaskFailure = taskFailure.copy(message = "Something else")
    f.taskFailureRepo.store(anotherTaskFailure).futureValue

    And("fetch it")
    val readFailure = f.taskFailureRepo.get(PathId("/some/app")).futureValue

    Then("the resulting failure is the one we stored LAST")
    readFailure should be(Some(anotherTaskFailure))
  }

  test("expunge works") {
    Given("an empty taskRepository")
    val f = new Fixture

    When("we store a taskFailure")
    f.taskFailureRepo.store(taskFailure).futureValue

    And("expunge it again")
    f.taskFailureRepo.delete(PathId("/some/app")).futureValue

    And("fetch it")
    val readFailure = f.taskFailureRepo.get(PathId("/some/app")).futureValue

    Then("the result is None")
    readFailure should be(None)
  }

  class Fixture {
    lazy val inMemoryStore = new InMemoryStore()
    lazy val entityStore = new MarathonStore[TaskFailure](
      inMemoryStore,
      metrics,
      () => TaskFailure.empty,
      prefix = "taskFailure:"
    )
    lazy val metricRegistry = new MetricRegistry
    lazy val metrics = new Metrics(metricRegistry)
    lazy val taskFailureRepo = new TaskFailureEntityRepository(entityStore, maxVersions = 1)(metrics = metrics)
  }
}
