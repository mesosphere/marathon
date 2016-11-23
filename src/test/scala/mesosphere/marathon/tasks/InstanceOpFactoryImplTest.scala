package mesosphere.marathon
package tasks

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryImpl
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper, Mockito }
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class InstanceOpFactoryImplTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Copy SlaveID from Offer to Task") {
    val f = new Fixture

    val appId = PathId("/test")
    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname(f.hostName)
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, f.clock.now()).getInstance()
    val app: AppDefinition = AppDefinition(id = appId, portDefinitions = List())
    val runningInstances = Seq(instance)

    val request = InstanceOpFactory.Request(app, offer, runningInstances, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    matchResult shouldBe a[OfferMatchResult.Match]
    val matched = matchResult.asInstanceOf[OfferMatchResult.Match]
    assert(matched.instanceOp.stateOp.possibleNewState.isDefined, "instanceOp should have a defined new state")
    assert(matched.instanceOp.stateOp.possibleNewState.get.tasksMap.size == 1, "new state should have 1 task")

    val (expectedTaskId, _) = matched.instanceOp.stateOp.possibleNewState.get.tasksMap.head
    val expectedTask = Task.LaunchedEphemeral(
      taskId = expectedTaskId,
      agentInfo = Instance.AgentInfo(
        host = f.hostName,
        agentId = Some(offer.getSlaveId.getValue),
        attributes = Vector.empty
      ),
      runSpecVersion = app.version,
      status = Task.Status(
        stagedAt = f.clock.now(),
        condition = Condition.Created,
        networkInfo = NetworkInfo(
          hasConfiguredIpAddress = false,
          hostPorts = Nil,
          effectiveIpAddress = Some(f.hostName),
          ipAddresses = None
        )
      )
    )
    val expectedInstance = Instance(expectedTaskId.instanceId, expectedTask.agentInfo, instance.state, Map(expectedTaskId -> expectedTask), runSpecVersion = app.version)
    assert(matched.instanceOp.stateOp == InstanceUpdateOperation.LaunchEphemeral(expectedInstance))
  }

  test("Normal app -> None (insufficient offer)") {
    Given("A normal app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the instanceOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("NoMatch is returned because there are already 2 launched tasks")
    matchResult shouldBe a[OfferMatchResult.NoMatch]
  }

  test("Normal app -> Launch") {
    Given("A normal app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.normalApp
    val offer = f.offer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("A Match with Launch is inferred")
    matchResult shouldBe a[OfferMatchResult.Match]
    matchResult.asInstanceOf[OfferMatchResult.Match].instanceOp shouldBe a[InstanceOp.LaunchTask]
  }

  test("Resident app -> None (insufficient offer)") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.insufficientOffer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("NoMatch is returned")
    matchResult shouldBe a[OfferMatchResult.NoMatch]
  }

  test("Resident app -> ReserveAndCreateVolumes fails because of insufficient disk resources") {
    Given("A resident app, an insufficient offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.offer
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("A NoMatch is returned because there is not enough disk space")
    matchResult shouldBe a[OfferMatchResult.NoMatch]
  }

  test("Resident app -> ReserveAndCreateVolumes succeeds") {
    Given("A resident app, a normal offer and no tasks")
    val f = new Fixture
    val app = f.residentApp
    val offer = f.offerWithSpaceForLocalVolume
    val runningTasks = Nil

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningTasks, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("A Match with ReserveAndCreateVolumes is returned")
    matchResult shouldBe a[OfferMatchResult.Match]
    matchResult.asInstanceOf[OfferMatchResult.Match].instanceOp shouldBe a[InstanceOp.ReserveAndCreateVolumes]
  }

  test("Resident app -> Launch succeeds") {
    Given("A resident app, an offer with persistent volumes and a matching task")
    val f = new Fixture
    val app = f.residentApp.copy(instances = 2)
    val localVolumeIdLaunched = LocalVolumeId(app.id, "persistent-volume-launched", "uuidLaunched")
    val localVolumeIdUnwanted = LocalVolumeId(app.id, "persistent-volume-unwanted", "uuidUnwanted")
    val localVolumeIdMatch = LocalVolumeId(app.id, "persistent-volume", "uuidMatch")
    val reservedInstance = f.residentReservedInstance(app.id, localVolumeIdMatch)
    val (reservedTaskId, _) = reservedInstance.tasksMap.head
    val offer = f.offerWithVolumes(
      reservedTaskId, localVolumeIdLaunched, localVolumeIdUnwanted, localVolumeIdMatch
    )
    val runningInstances = Seq(
      f.residentLaunchedInstance(app.id, localVolumeIdLaunched),
      reservedInstance)

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningInstances, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("A Match with a Launch is returned")
    matchResult shouldBe a[OfferMatchResult.Match]
    val matched = matchResult.asInstanceOf[OfferMatchResult.Match]
    matched.instanceOp shouldBe a[InstanceOp.LaunchTask]

    And("the taskInfo contains the correct persistent volume")
    val taskInfoResources = matched.instanceOp.offerOperations.head.getLaunch.getTaskInfos(0).getResourcesList
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
    val runningInstances = Seq(f.residentLaunchedInstance(app.id, usedVolumeId))
    val (runningTaskId, _) = runningInstances.head.tasksMap.head
    val offer = f.offerWithVolumes(runningTaskId, offeredVolumeId)

    When("We infer the taskOp")
    val request = InstanceOpFactory.Request(app, offer, runningInstances, additionalLaunches = 1)
    val matchResult = f.instanceOpFactory.matchOfferRequest(request)

    Then("A None is returned because there is already a launched Task")
    matchResult shouldBe a[OfferMatchResult.NoMatch]
  }

  class Fixture {
    import mesosphere.marathon.test.{ MarathonTestHelper => MTH }
    val instanceTracker = mock[InstanceTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("test"))
    implicit val clock = ConstantClock()
    val instanceOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(config)
    val hostName = "some_host"

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume()
    def residentReservedInstance(appId: PathId, volumeIds: LocalVolumeId*) = TestInstanceBuilder.newBuilder(appId).addTaskResidentReserved(volumeIds: _*).getInstance()
    def residentLaunchedInstance(appId: PathId, volumeIds: LocalVolumeId*) = TestInstanceBuilder.newBuilder(appId).addTaskResidentLaunched(volumeIds: _*).getInstance()
    def offer = MTH.makeBasicOffer().build()
    def offerWithSpaceForLocalVolume = MTH.makeBasicOffer(disk = 1025).build()
    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()

    def offerWithVolumes(taskId: Task.Id, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, localVolumeIds: _*)
  }

}
