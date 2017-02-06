package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.Future

class InstancesLoaderImplTest extends AkkaUnitTest {
  class Fixture {
    lazy val instanceRepository = mock[InstanceRepository]
    lazy val loader = new InstancesLoaderImpl(instanceRepository)

    def verifyNoMoreInteractions(): Unit = noMoreInteractions(instanceRepository)
  }
  "InstanceLoaderImpl" should {
    "loading no tasks" in {
      val f = new Fixture

      Given("no tasks")
      f.instanceRepository.ids() returns Source.empty

      When("loadTasks is called")
      val loaded = f.loader.load()

      Then("taskRepository.ids gets called")
      verify(f.instanceRepository).ids()

      And("our data is empty")
      loaded.futureValue.allInstances should be(empty)

      And("there are no more interactions")
      f.verifyNoMoreInteractions()
    }

    "loading multiple tasks for multiple apps" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app2Id = PathId("/app2")
      val app2Instance1 = TestInstanceBuilder.newBuilder(app2Id).getInstance()
      val instances = Seq(app1Instance1, app1Instance2, app2Instance1)

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data is correct")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val appData1 = InstanceTracker.SpecInstances.forInstances(app1Id, Seq(app1Instance1, app1Instance2))
      val appData2 = InstanceTracker.SpecInstances.forInstances(app2Id, Seq(app2Instance1))
      val expectedData = InstanceTracker.InstancesBySpec.of(appData1, appData2)
      loaded.futureValue should equal(expectedData)
    }
  }
}