package mesosphere.marathon.tasks

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.impl.TaskOpFactoryImpl
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class TaskOpFactoryImplTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Copy SlaveID from Offer to Task") {
    val f = new Fixture

    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname("some_host")
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val app: AppDefinition = AppDefinition(portDefinitions = List())
    val runningTasks: Set[Task] = Set(
      MarathonTestHelper.mininimalTask("some task ID")
    )

    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val inferredTaskOp = f.taskOpFactory.buildTaskOp(request)

    val expectedTask = Task.LaunchedEphemeral(
      taskId = inferredTaskOp.fold(Task.Id("failure"))(_.taskId),
      agentInfo = Task.AgentInfo(
        host = "some_host",
        agentId = Some(offer.getSlaveId.getValue),
        attributes = List.empty
      ),
      appVersion = app.version,
      status = Task.Status(
        stagedAt = f.clock.now()
      ),
      hostPorts = Seq.empty
    )
    assert(inferredTaskOp.isDefined, "task op is not empty")
    assert(inferredTaskOp.get.stateOp == TaskStateOp.LaunchEphemeral(expectedTask))
  }

  test("Normal app -> None (insufficient offer)") {
    Given("A normal app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("None is returned because there are already 2 launched tasks")
    taskOp shouldBe empty
  }

  test("Normal app -> Launch") {
    Given("A normal app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.offer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A Launch is inferred")
    taskOp shouldBe a[Some[TaskOp.Launch]]
  }

  test("Resident app -> None (insufficient offer)") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("None is returned")
    taskOp shouldBe empty
  }

  test("Resident app -> ReserveAndCreateVolumes fails because of insufficient disk resources") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.offer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A no is returned because there is not enough disk space")
    taskOp shouldBe None
  }

  test("Resident app -> ReserveAndCreateVolumes succeeds") {
    Given("A resident app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.offerWithSpaceForLocalVolume
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A ReserveAndCreateVolumes is returned")
    taskOp shouldBe a[Some[TaskOp.ReserveAndCreateVolumes]]
  }

  test("Resident app -> Launch succeeds") {
    Given("A resident app, an offer with persistent volumes and a matching task")
    val f = new Fixture
    val app = f.residentApp.copy(instances = 2)
    val localVolumeIdLaunched = LocalVolumeId(app.id, "persistent-volume-launched", "uuidLaunched")
    val localVolumeIdUnwanted = LocalVolumeId(app.id, "persistent-volume-unwanted", "uuidUnwanted")
    val localVolumeIdMatch = LocalVolumeId(app.id, "persistent-volume", "uuidMatch")
    val reservedTask = f.residentReservedTask(app.id, localVolumeIdMatch)
    val offer = f.offerWithVolumes(
      reservedTask.taskId.idString, localVolumeIdLaunched, localVolumeIdUnwanted, localVolumeIdMatch
    )
    val runningTasks = Seq(
      f.residentLaunchedTask(app.id, localVolumeIdLaunched),
      reservedTask)

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

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
    val runningTasks = Seq(f.residentLaunchedTask(app.id, usedVolumeId))
    val offer = f.offerWithVolumes(runningTasks.head.taskId.idString, offeredVolumeId)

    When("We infer the taskOp")
    val request = TaskOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A None is returned because there is already a launched Task")
    taskOp shouldBe empty
  }

  class Fixture {
    import mesosphere.marathon.{ MarathonTestHelper => MTH }
    val taskTracker = mock[TaskTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("test"), principal = Some("principal"))
    val clock = ConstantClock()
    val taskOpFactory: TaskOpFactory = new TaskOpFactoryImpl(config, clock)

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume()
    def normalLaunchedTask(appId: PathId) = MTH.mininimalTask(appId.toString)
    def residentReservedTask(appId: PathId, volumeIds: LocalVolumeId*) = MTH.residentReservedTask(appId, volumeIds: _*)
    def residentLaunchedTask(appId: PathId, volumeIds: LocalVolumeId*) = MTH.residentLaunchedTask(appId, volumeIds: _*)
    def offer = MTH.makeBasicOffer().build()
    def offerWithSpaceForLocalVolume = MTH.makeBasicOffer(disk = 1025).build()
    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()

    def offerWithVolumes(taskId: String, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, localVolumeIds: _*)
  }

}
