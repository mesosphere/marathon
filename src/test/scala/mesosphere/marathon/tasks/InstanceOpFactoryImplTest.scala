package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus, TestTaskBuilder }
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryImpl
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper, Mockito }
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class InstanceOpFactoryImplTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Copy SlaveID from Offer to Task") {
    val f = new Fixture

    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname("some_host")
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val app: AppDefinition = AppDefinition(id = PathId("/test"), portDefinitions = List())
    val runningTasks: Set[Task] = Set(
      TestTaskBuilder.Creator.minimalTask(Task.Id.forRunSpec(PathId("/test")))
    )

    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val inferredTaskOp = f.taskOpFactory.buildTaskOp(request)

    assert(inferredTaskOp.isDefined, "instanceOp should be defined")
    assert(inferredTaskOp.get.stateOp.possibleNewState.isDefined, "instanceOp should have a defined new state")
    assert(inferredTaskOp.get.stateOp.possibleNewState.get.tasks.size == 1, "new state should have 1 task")

    val expectedTask = Task.LaunchedEphemeral(
      taskId = inferredTaskOp.get.stateOp.possibleNewState.get.tasks.head.taskId,
      agentInfo = Instance.AgentInfo(
        host = "some_host",
        agentId = Some(offer.getSlaveId.getValue),
        attributes = Vector.empty
      ),
      runSpecVersion = app.version,
      status = Task.Status(
        stagedAt = f.clock.now(),
        taskStatus = InstanceStatus.Created
      ),
      hostPorts = Seq.empty
    )
    assert(inferredTaskOp.isDefined, "task op is not empty")
    assert(inferredTaskOp.get.stateOp == InstanceUpdateOperation.LaunchEphemeral(expectedTask))
  }

  test("Normal app -> None (insufficient offer)") {
    Given("A normal app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
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
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A Launch is inferred")
    taskOp.value shouldBe a[InstanceOp.LaunchTask]
  }

  test("Resident app -> None (insufficient offer)") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
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
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
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
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A ReserveAndCreateVolumes is returned")
    taskOp.value shouldBe a[InstanceOp.ReserveAndCreateVolumes]
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
      reservedTask.taskId, localVolumeIdLaunched, localVolumeIdUnwanted, localVolumeIdMatch
    )
    val runningTasks = Seq(
      f.residentLaunchedTask(app.id, localVolumeIdLaunched),
      reservedTask)

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A Launch is returned")
    taskOp.value shouldBe a[InstanceOp.LaunchTask]

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
    val offer = f.offerWithVolumes(runningTasks.head.taskId, offeredVolumeId)

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val taskOp = f.taskOpFactory.buildTaskOp(request)

    Then("A None is returned because there is already a launched Task")
    taskOp shouldBe empty
  }

  class Fixture {
    import mesosphere.marathon.test.{ MarathonTestHelper => MTH }
    val taskTracker = mock[InstanceTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("test"))
    implicit val clock = ConstantClock()
    val taskOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(config)

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume()
    def residentReservedTask(appId: PathId, volumeIds: LocalVolumeId*) = TestTaskBuilder.Creator.residentReservedTask(appId, volumeIds: _*)
    def residentLaunchedTask(appId: PathId, volumeIds: LocalVolumeId*) = TestTaskBuilder.Creator.residentLaunchedTask(appId, volumeIds: _*)
    def offer = MTH.makeBasicOffer().build()
    def offerWithSpaceForLocalVolume = MTH.makeBasicOffer(disk = 1025).build()
    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()

    def offerWithVolumes(taskId: Task.Id, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, localVolumeIds: _*)
  }

}
