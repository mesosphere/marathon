package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.storage.repository.impl.legacy.DeploymentEntityRepository
import mesosphere.marathon.core.storage.repository.impl.legacy.store.MarathonStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ GivenWhenThen, Matchers }

class MigrationTo1_2Test extends MarathonSpec with GivenWhenThen with Matchers {
  import mesosphere.FutureTestSupport._

  class Fixture {
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val store = new InMemoryStore()
    lazy val deploymentStore = new MarathonStore[DeploymentPlan](
      store = store,
      metrics = metrics,
      newState = () => DeploymentPlan.empty,
      prefix = "deployment:"
    )
    lazy val deploymentRepo = new DeploymentEntityRepository(deploymentStore)(metrics = metrics)
    lazy val migration = new MigrationTo1_2(deploymentRepo)
  }

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(1, Seconds))

  test("should remove deployment version nodes, but keep deployment nodes") {
    Given("some deployment version nodes, a proper deployment node and an unrelated node")
    val f = new Fixture

    f.store.create("deployment:265fe17c-2979-4ab6-b906-9c2b34f9c429:2016-06-23T22:16:03.880Z", IndexedSeq.empty)
    f.store.create("deployment:42c6b840-5a4b-4110-a7d9-d4835f7499b9:2016-06-13T18:47:15.862Z", IndexedSeq.empty)
    f.store.create("deployment:fcabfa75-7756-4bc8-94b3-c9d5b2abd38c", IndexedSeq.empty)
    f.store.create("foo:bar", IndexedSeq.empty)

    When("migrating")
    f.migration.migrate().futureValue

    Then("the deployment version nodes are removed, all other nodes are kept")
    val nodeNames: Seq[String] = f.store.allIds().futureValue
    nodeNames should contain theSameElementsAs Seq("deployment:fcabfa75-7756-4bc8-94b3-c9d5b2abd38c", "foo:bar")
  }

}
