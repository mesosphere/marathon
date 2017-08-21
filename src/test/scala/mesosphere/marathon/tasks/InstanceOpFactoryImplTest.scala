package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryImpl
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.state.{ AgentTestDefaults, NetworkInfo }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.Inside

import scala.collection.immutable.Seq

class InstanceOpFactoryImplTest extends UnitTest with Inside {

  "InstanceOpFactoryImpl" should {
    "Copy SlaveID from Offer to Task" in {
      val f = new Fixture

      val appId = PathId("/test")
      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname(f.defaultHostName)
        .setSlaveId(SlaveID("some slave ID"))
        .build()
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, f.clock.now()).getInstance()
      val app: AppDefinition = AppDefinition(id = appId, portDefinitions = List())
      val runningInstances = Map(instance.instanceId -> instance)

      val request = InstanceOpFactory.Request(app, offer, runningInstances, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      val matched = inside(matchResult) {
        case matched: OfferMatchResult.Match =>
          assert(matched.instanceOp.stateOp.possibleNewState.isDefined, "instanceOp should have a defined new state")
          assert(matched.instanceOp.stateOp.possibleNewState.get.tasksMap.size == 1, "new state should have 1 task")
          matched
      }

      val (expectedTaskId, _) = matched.instanceOp.stateOp.possibleNewState.get.tasksMap.head
      val expectedTask = Task.LaunchedEphemeral(
        taskId = expectedTaskId,
        runSpecVersion = app.version,
        status = Task.Status(
          stagedAt = f.clock.now(),
          condition = Condition.Created,
          networkInfo = NetworkInfo(
            f.defaultHostName,
            hostPorts = Nil,
            ipAddresses = Nil
          )
        )
      )
      val expectedAgentInfo = Instance.AgentInfo(
        host = f.defaultHostName,
        agentId = Some(offer.getSlaveId.getValue),
        attributes = Vector.empty
      )

      val expectedInstance = Instance(
        expectedTaskId.instanceId, expectedAgentInfo, instance.state, Map(expectedTaskId -> expectedTask),
        runSpecVersion = app.version, app.unreachableStrategy)
      assert(matched.instanceOp.stateOp == InstanceUpdateOperation.LaunchEphemeral(expectedInstance))
    }

    "Normal app -> None (insufficient offer)" in {
      Given("A normal app, an insufficient offer and no tasks")
      val f = new Fixture
      val app = f.normalApp
      val offer = f.insufficientOffer

      When("We infer the instanceOp")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("NoMatch is returned because there are already 2 launched tasks")
      matchResult shouldBe a[OfferMatchResult.NoMatch]
    }

    "Normal app -> Launch" in {
      Given("A normal app, a normal offer and no tasks")
      val f = new Fixture
      val app = f.normalApp
      val offer = f.offer

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with Launch is inferred")
      inside(matchResult) {
        case mr: OfferMatchResult.Match =>
          mr.instanceOp shouldBe a[InstanceOp.LaunchTask]
      }
    }

    "Resident app -> None (insufficient offer)" in {
      Given("A resident app, an insufficient offer and no tasks")
      val f = new Fixture
      val app = f.residentApp
      val offer = f.insufficientOffer

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("NoMatch is returned")
      matchResult shouldBe a[OfferMatchResult.NoMatch]
    }

    "Resident app -> ReserveAndCreateVolumes fails because of insufficient disk resources" in {
      Given("A resident app, an insufficient offer and no tasks")
      val f = new Fixture
      val app = f.residentApp
      val offer = f.offer

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A NoMatch is returned because there is not enough disk space")
      matchResult shouldBe a[OfferMatchResult.NoMatch]
    }

    "Resident app -> ReserveAndCreateVolumes succeeds" in {
      Given("A resident app, a normal offer and no tasks")
      val f = new Fixture
      val app = f.residentApp
      val offer = f.offerWithSpaceForLocalVolume

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, Map.empty, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with ReserveAndCreateVolumes is returned")
      inside(matchResult) {
        case mr: OfferMatchResult.Match =>
          mr.instanceOp shouldBe a[InstanceOp.ReserveAndCreateVolumes]
      }
    }

    "Resident app -> Launch succeeds" in {
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
      val runningInstances = Instance.instancesById(Seq(
        f.residentLaunchedInstance(app.id, localVolumeIdLaunched),
        reservedInstance))

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, runningInstances, additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with a Launch is returned")
      val matched = inside(matchResult) {
        case matched: OfferMatchResult.Match =>
          matched.instanceOp shouldBe a[InstanceOp.LaunchTask]
          matched
      }

      And("the taskInfo contains the correct persistent volume")
      val taskInfoResources = matched.instanceOp.offerOperations.head.getLaunch.getTaskInfos(0).getResourcesList
      val found = taskInfoResources.find { resource =>
        resource.hasDisk && resource.getDisk.hasPersistence &&
          resource.getDisk.getPersistence.getId == localVolumeIdMatch.idString
      }
      found should not be empty
    }

    "Resident app -> None (enough launched tasks)" in {
      Given("A resident app, a matching offer with persistent volumes but already enough launched tasks")
      val f = new Fixture
      val app = f.residentApp
      val usedVolumeId = LocalVolumeId(app.id, "unwanted-persistent-volume", "uuid1")
      val offeredVolumeId = LocalVolumeId(app.id, "unwanted-persistent-volume", "uuid2")
      val runningInstances = Seq(f.residentLaunchedInstance(app.id, usedVolumeId))
      val (runningTaskId, _) = runningInstances.head.tasksMap.head
      val offer = f.offerWithVolumes(runningTaskId, offeredVolumeId)

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(app, offer, Instance.instancesById(runningInstances), additionalLaunches = 1)
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A None is returned because there is already a launched Task")
      matchResult shouldBe a[OfferMatchResult.NoMatch]
    }

    // There are times when an agent gets a new agentId after a reboot. There might have been a task using
    // reservations and a persistent volume on agent-1 in the past. When agent-1 is rebooted and looses
    // the task, Marathon might see the resources offered from agent-2 in the future - if the agent has
    // been re-registered with that new ID. In order to report correct AgentInfo, the AgentInfo needs to recreated
    // each time we launch on an existing reservation.
    "update the agentInfo based on the used offer" in {
      val f = new Fixture
      val app = f.residentApp
      val volumeId = LocalVolumeId(app.id, "/path", "uuid1")
      val existingReservedInstance = f.residentReservedInstance(app.id, volumeId)
      existingReservedInstance.agentInfo.host shouldBe f.defaultHostName
      existingReservedInstance.agentInfo.agentId shouldBe Some(f.defaultAgentId)

      val taskId = existingReservedInstance.appTask.taskId
      val updatedHostName = "updatedHostName"
      val updatedAgentId = "updatedAgentId"
      val offer = f.offerWithVolumes(taskId, updatedHostName, updatedAgentId, volumeId)

      val request = InstanceOpFactory.Request(app, offer, Map(existingReservedInstance.instanceId -> existingReservedInstance), additionalLaunches = 1)
      val result = f.instanceOpFactory.matchOfferRequest(request)

      inside(result) {
        case m: OfferMatchResult.Match =>
          inside(m.instanceOp) {
            case launchTask: InstanceOp.LaunchTask =>
              inside(launchTask.stateOp) {
                case launchOnReservation: InstanceUpdateOperation.LaunchOnReservation =>
                  launchOnReservation.agentInfo.host shouldBe updatedHostName
                  launchOnReservation.agentInfo.agentId shouldBe Some(updatedAgentId)
              }
          }
      }
    }
  }

  class Fixture {
    import mesosphere.marathon.test.{ MarathonTestHelper => MTH }
    val instanceTracker = mock[InstanceTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("test"))
    implicit val clock = new SettableClock()
    val instanceOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(config)
    val defaultHostName = AgentTestDefaults.defaultHostName
    val defaultAgentId = AgentTestDefaults.defaultAgentId

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume()
    def residentReservedInstance(appId: PathId, volumeIds: LocalVolumeId*) = TestInstanceBuilder.newBuilder(appId).addTaskResidentReserved(volumeIds: _*).getInstance()
    def residentLaunchedInstance(appId: PathId, volumeIds: LocalVolumeId*) = TestInstanceBuilder.newBuilder(appId).addTaskResidentLaunched(volumeIds: _*).getInstance()
    def offer = MTH.makeBasicOffer().build()
    def offerWithSpaceForLocalVolume = MTH.makeBasicOffer(disk = 1025).build()
    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()

    def offerWithVolumes(taskId: Task.Id, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, defaultHostName, defaultAgentId, localVolumeIds: _*)
    def offerWithVolumes(taskId: Task.Id, hostname: String, agentId: String, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, hostname, agentId, localVolumeIds: _*)
  }

}
