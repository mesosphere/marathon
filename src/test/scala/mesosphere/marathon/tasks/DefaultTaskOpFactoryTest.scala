package mesosphere.marathon.tasks

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.impl.DefaultTaskOpFactory
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.{ GivenWhenThen, Matchers }

class DefaultTaskOpFactoryTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Copy SlaveID from Offer to Task") {
    val f = new Fixture

    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname("some_host")
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val appDefinition: AppDefinition = AppDefinition(portDefinitions = List())
    val runningTasks: Set[Task] = Set(
      MarathonTestHelper.mininimalTask("some task ID")
    )

    val inferredTaskOp = f.taskOpFactory.inferTaskOp(appDefinition, offer, runningTasks)

    val expectedTask = Task(
      taskId = Task.Id("some task ID"),
      agentInfo = Task.AgentInfo(
        host = "some_host",
        agentId = Some(offer.getSlaveId.getValue),
        attributes = List.empty
      ),
      launched = Some(
        Task.Launched(
          appVersion = appDefinition.version,
          status = Task.Status(
            stagedAt = f.clock.now()
          ),
          networking = Task.HostPorts(List.empty)
        )
      )
    )
    assert(inferredTaskOp.isDefined, "task op is not empty")
    assert(inferredTaskOp.get.newTask.copy(taskId = expectedTask.taskId) == expectedTask)
  }

  test("Normal app -> None (insufficient offer)") {
    Given("A normal app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.insufficientOffer
    val tasks = Nil

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("None is returned because there are already 2 launched tasks")
    taskOp shouldBe empty
  }

  test("Normal app -> Launch") {
    Given("A normal app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.offer
    val tasks = Nil

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("A Launch is inferred")
    taskOp shouldBe a[Some[TaskOp.Launch]]
  }

  test("Resident app -> None (insufficient offer)") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.insufficientOffer
    val tasks = Nil

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("None is returned")
    taskOp shouldBe empty
  }

  test("Resident app -> ReserveAndCreateVolumes") {
    Given("A resident app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.offer
    val tasks = Nil

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("A ReserveAndCreateVolumes is returned")
    taskOp shouldBe a[Some[TaskOp.ReserveAndCreateVolumes]]
  }

  test("Resident app -> Launch") {
    Given("A resident app, an offer with persistent volumes and a matching task")
    val f = new Fixture
    val app = f.residentApp.copy(instances = 2)
    val localVolumeIdLaunched = LocalVolumeId(app.id, "persistent-volume-launched", "uuidLaunched")
    val localVolumeIdUnwanted = LocalVolumeId(app.id, "persistent-volume-unwanted", "uuidUnwanted")
    val localVolumeIdMatch = LocalVolumeId(app.id, "persistent-volume", "uuidMatch")
    val offer = f.offerWithVolumes(localVolumeIdLaunched, localVolumeIdUnwanted, localVolumeIdMatch)
    val tasks = Seq(
      f.residentLaunchedTask(app.id, localVolumeIdLaunched),
      f.residentReservedTask(app.id, localVolumeIdMatch))

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("A Launch is returned")
    taskOp shouldBe a[Some[TaskOp.Launch]]

    And("the taskInfo contains the correct persistent volume")
    import scala.collection.JavaConverters._
    val taskInfoResources = taskOp.get.offerOperations.head.getLaunch.getTaskInfos(0).getResourcesList.asScala
    val found = taskInfoResources.find { resource =>
      resource.hasDisk && resource.getDisk.hasPersistence &&
        resource.getDisk.getPersistence.getId == localVolumeIdMatch.idString
    }
    found should not be empty
  }

  test("Resident app -> None (enough launched tasks)") {
    Given("A resident app, a matching offer with persistent volumes but already enough launched tasks")
    val f = new Fixture
    val app = f.residentApp
    val usedVolumeId = LocalVolumeId(app.id, "unwanted-persistent-volume", "uuid1")
    val offeredVolumeId = LocalVolumeId(app.id, "unwanted-persistent-volume", "uuid2")
    val offer = f.offerWithVolumes(offeredVolumeId)
    val tasks = Seq(f.residentLaunchedTask(app.id, usedVolumeId))

    When("We infer the taskOp")
    val taskOp = f.taskOpFactory.inferTaskOp(app, offer, tasks)

    Then("A None is returned because there is already a launched Task")
    taskOp shouldBe empty
  }

  class Fixture {
    import mesosphere.marathon.{ MarathonTestHelper => MTH }
    val taskTracker = mock[TaskTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("role"), principal = Some("principal"))
    val clock = ConstantClock()
    val taskOpFactory: TaskOpFactory = new DefaultTaskOpFactory(config, clock)

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume()
    def normalLaunchedTask(appId: PathId, volumeIds: LocalVolumeId*) = MTH.mininimalTask(appId).copy(launched = Some(MTH.taskLaunched))
    def residentReservedTask(appId: PathId, volumeIds: LocalVolumeId*) =
      MTH.residentReservedTask(appId, volumeIds: _*)
    def residentLaunchedTask(appId: PathId, volumeIds: LocalVolumeId*) =
      MTH.residentReservedTask(appId, volumeIds: _*).copy(launched = Some(MTH.taskLaunched))
    def offer = MTH.makeBasicOffer().build()
    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()
    def offerWithVolumes(localVolumeIds: LocalVolumeId*) = MTH.offerWithVolumes(localVolumeIds: _*)
  }

}
