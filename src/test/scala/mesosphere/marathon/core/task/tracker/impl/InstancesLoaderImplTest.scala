package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, VersionInfo}
import mesosphere.marathon.storage.repository.{GroupRepository, InstanceRepository, InstanceView}
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}

import scala.concurrent.Future
import scala.concurrent.duration._

class InstancesLoaderImplTest extends AkkaUnitTest {
  class Fixture {
    lazy val instanceRepository = mock[InstanceRepository]
    lazy val groupRepository = mock[GroupRepository]
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val loader = new InstancesLoaderImpl(InstanceView(instanceRepository, groupRepository), config)

    val clock = new SettableClock()

    def verifyNoMoreInteractions(): Unit = noMoreInteractions(instanceRepository)
  }
  "InstanceLoaderImpl" should {
    "load no instances" in {
      val f = new Fixture

      Given("no instances")
      f.instanceRepository.ids() returns Source.empty

      When("load is called")
      val loaded = f.loader.load().futureValue

      Then("our data is empty")
      loaded.allInstances should be(empty)
    }

    "load multiple instances for multiple apps" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1 = app1Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app1Id), eq(app1.version.toOffsetDateTime))(any) returns Future.successful(Some(app1))

      val app2Id = PathId("/app2")
      val app2Instance1 = TestInstanceBuilder.newBuilder(app2Id).getInstance()
      val app2 = app2Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app2Id), eq(app2.version.toOffsetDateTime))(any) returns Future.successful(Some(app2))

      val instances = Seq(
        state.Instance.fromCoreInstance(app1Instance1),
        state.Instance.fromCoreInstance(app1Instance2),
        state.Instance.fromCoreInstance(app2Instance1)
      )

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data is correct")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val expectedData = InstanceTracker.InstancesBySpec.forInstances(app1Instance1, app1Instance2, app2Instance1)
      loaded.futureValue should equal(expectedData)
    }

    "select latest run spec if version was not found" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1 = app1Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app1Id), eq(app1.version.toOffsetDateTime))(any) returns Future.successful(Some(app1))

      And(s"no run spec for app 2 version ${f.clock.now()}")
      val app2Id = PathId("/app2")
      val app2 = AppDefinition(id = app2Id, versionInfo = VersionInfo.OnlyVersion(f.clock.now()))
      val app2Instance1 = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(app2Id)).copy(runSpec = app2)
      f.groupRepository.runSpecVersion(eq(app2Id), eq(app2Instance1.runSpecVersion.toOffsetDateTime))(any) returns Future.successful(None)

      val newVersion = f.clock.now() + 2.days
      And(s"a newer run spec for app 2 version $newVersion")
      val app2Newer = app2.copy(versionInfo = VersionInfo.OnlyVersion(newVersion))
      f.groupRepository.latestRunSpec(eq(app2Id))(any, any) returns Future.successful(Some(app2Newer))

      val instances = Seq(
        state.Instance.fromCoreInstance(app1Instance1),
        state.Instance.fromCoreInstance(app1Instance2),
        state.Instance.fromCoreInstance(app2Instance1)
      )

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data includes an instance for app 2 with the latest run spec")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val expectedData = InstanceTracker.InstancesBySpec.forInstances(app1Instance1, app1Instance2, app2Instance1.copy(runSpec = app2Newer))
      loaded.futureValue should equal(expectedData)
    }

    "ignore instances without a run spec" in {
      val f = new Fixture

      Given("instances for multiple runSpecs")
      val app1Id = PathId("/app1")
      val app1Instance1 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1Instance2 = TestInstanceBuilder.newBuilder(app1Id).getInstance()
      val app1 = app1Instance1.runSpec
      f.groupRepository.runSpecVersion(eq(app1Id), eq(app1.version.toOffsetDateTime))(any) returns Future.successful(Some(app1))

      val app2Id = PathId("/app2")
      val app2Instance1 = TestInstanceBuilder.newBuilder(app2Id).getInstance()
      f.groupRepository.runSpecVersion(eq(app2Id), eq(app2Instance1.runSpecVersion.toOffsetDateTime))(any) returns Future.successful(None)
      f.groupRepository.latestRunSpec(eq(app2Id))(any, any) returns Future.successful(None)

      val instances = Seq(
        state.Instance.fromCoreInstance(app1Instance1),
        state.Instance.fromCoreInstance(app1Instance2),
        state.Instance.fromCoreInstance(app2Instance1)
      )

      f.instanceRepository.ids() returns Source(instances.map(_.instanceId)(collection.breakOut))
      for (instance <- instances) {
        f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
      }

      f.instanceRepository.delete(app2Instance1.instanceId) returns Future.successful(Done)

      When("load is called")
      val loaded = f.loader.load()

      Then("the resulting data does not include instances from app2")
      // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
      val expectedData = InstanceTracker.InstancesBySpec.forInstances(app1Instance1, app1Instance2)
      loaded.futureValue should equal(expectedData)

      And("the instance for app2 was expunged")
      verify(f.instanceRepository).delete(app2Instance1.instanceId)
    }
  }
}