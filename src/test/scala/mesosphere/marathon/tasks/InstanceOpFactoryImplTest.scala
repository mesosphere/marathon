package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{Instance, LocalVolumeId, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryImpl
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.{AgentTestDefaults, NetworkInfo}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.raml.{ExecutorResources, Resources}
import mesosphere.marathon.state._
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import mesosphere.mesos.TaskBuilderConstants
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.Inside

import scala.collection.immutable.Seq

import scala.jdk.CollectionConverters._

class InstanceOpFactoryImplTest extends UnitTest with Inside {

  "InstanceOpFactoryImpl" should {
    "Copy SlaveID from Offer to Task" in {
      val f = new Fixture

      val appId = AbsolutePathId("/test")
      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname(f.defaultHostName)
        .setSlaveId(SlaveID("some slave ID"))
        .build()
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, f.clock.now()).getInstance()
      val app: AppDefinition = AppDefinition(id = appId, portDefinitions = List(), role = "*")
      val scheduledInstance = Instance.scheduled(app, Instance.Id.forRunSpec(appId))
      val runningInstances = Map(instance.instanceId -> instance)

      val request = InstanceOpFactory.Request(offer, runningInstances, scheduledInstances = NonEmptyIterable(scheduledInstance))
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      val matched = inside(matchResult) {
        case matched: OfferMatchResult.Match => matched
      }

      val expectedTaskId = Task.Id(scheduledInstance.instanceId)
      val expectedTask = Task(
        taskId = expectedTaskId,
        runSpecVersion = app.version,
        status = Task.Status(
          stagedAt = f.clock.now(),
          condition = Condition.Provisioned,
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
        region = None,
        zone = None,
        attributes = Vector.empty
      )

      val expectedState = instance.state.copy(condition = Condition.Provisioned)
      val provisionOp = InstanceUpdateOperation.Provision(expectedTaskId.instanceId, expectedAgentInfo, app, Map(expectedTaskId -> expectedTask), expectedState.since)
      matched.instanceOp.stateOp should be(provisionOp)
    }

    "Normal app -> Launch" in {
      Given("A normal app, a normal offer and no tasks")
      val f = new Fixture
      val app = f.normalApp
      val offer = f.offer

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(Instance.scheduled(app)))
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with Launch is inferred")
      inside(matchResult) {
        case mr: OfferMatchResult.Match =>
          mr.instanceOp shouldBe an[InstanceOp.LaunchTask]
      }
    }

    "Resident app -> ReserveAndCreateVolumes succeeds" in {
      Given("A resident app, a normal offer and no tasks")
      val f = new Fixture
      val app = f.residentApp
      val offer = f.offerWithSpaceForLocalVolume

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(Instance.scheduled(app)))
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with ReserveAndCreateVolumes is returned")
      inside(matchResult) {
        case mr: OfferMatchResult.Match =>
          mr.instanceOp shouldBe an[InstanceOp.ReserveAndCreateVolumes]
      }
    }

    "Resident pod -> ReserveAndCreateVolumes succeeds" in {
      Given("A resident pod, a normal offer and no tasks")
      val f = new Fixture
      val pod = PodDefinition(
        AbsolutePathId("/test-pod"),
        containers = Seq(MesosContainer(
          name = "first",
          resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
          volumeMounts = Seq(VolumeMount(volumeName = Some("pst"), mountPath = "persistent-volume")))
        ),
        volumes = Seq(PersistentVolume(name = Some("pst"), persistent = PersistentVolumeInfo(10))),
        role = "test"
      )
      val offer = f.offerWithSpaceForLocalVolume

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(Instance.scheduled(pod)))
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with ReserveAndCreateVolumes is returned")
      inside(matchResult) {
        case mr: OfferMatchResult.Match =>
          mr.instanceOp shouldBe an[InstanceOp.ReserveAndCreateVolumes]
      }
    }

    "Resident app -> Launch succeeds" in {
      Given("A resident app, an offer with persistent volumes and a matching task")
      val f = new Fixture
      val app = f.residentApp.copy(instances = 2)
      val localVolumeIdLaunched = LocalVolumeId(app.id, "persistent-volume-launched", "uuidLaunched")
      val localVolumeIdUnwanted = LocalVolumeId(app.id, "persistent-volume-unwanted", "uuidUnwanted")
      val localVolumeIdMatch = LocalVolumeId(app.id, "persistent-volume", "uuidMatch")
      val reservedInstance = f.scheduledReservedInstance(app.id, localVolumeIdMatch)
      val reservedTaskId = Task.Id(reservedInstance.instanceId)
      val offer = f.offerWithVolumes(
        reservedTaskId, localVolumeIdLaunched, localVolumeIdUnwanted, localVolumeIdMatch
      )
      val runningInstances = Instance.instancesById(Seq(
        f.residentLaunchedInstance(app.id, localVolumeIdLaunched)))

      When("We infer the taskOp")
      val request = InstanceOpFactory.Request(offer, runningInstances, scheduledInstances = NonEmptyIterable(reservedInstance))
      val matchResult = f.instanceOpFactory.matchOfferRequest(request)

      Then("A Match with a Launch is returned")
      val matched = inside(matchResult) {
        case matched: OfferMatchResult.Match =>
          matched.instanceOp shouldBe an[InstanceOp.LaunchTask]
          matched
      }

      And("the taskInfo contains the correct persistent volume")
      val taskInfoResources = matched.instanceOp.offerOperations.head.getLaunch.getTaskInfos(0).getResourcesList
      val found = taskInfoResources.asScala.find { resource =>
        resource.hasDisk && resource.getDisk.hasPersistence &&
          resource.getDisk.getPersistence.getId == localVolumeIdMatch.idString
      }
      found should not be empty
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
      val existingReservedInstance = f.scheduledReservedInstance(app.id, volumeId)

      val taskId = Task.Id(existingReservedInstance.instanceId)
      val updatedHostName = "updatedHostName"
      val updatedAgentId = "updatedAgentId"
      val offer = f.offerWithVolumes(taskId, updatedHostName, updatedAgentId, volumeId)

      val request = InstanceOpFactory.Request(offer, Map.empty, scheduledInstances = NonEmptyIterable(existingReservedInstance))
      val result = f.instanceOpFactory.matchOfferRequest(request)

      inside(result) {
        case m: OfferMatchResult.Match =>
          inside(m.instanceOp) {
            case launchTask: InstanceOp.LaunchTask =>
              inside(launchTask.stateOp) {
                case provision: InstanceUpdateOperation.Provision =>
                  provision.agentInfo.host shouldBe updatedHostName
                  provision.agentInfo.agentId shouldBe Some(updatedAgentId)
              }
          }
      }
    }

    "enforceRole property is propagated to task environment for pods" in {
      val f = new Fixture
      f.rootGroup = f.rootGroup.putGroup(Group(AbsolutePathId("/dev"), enforceRole = true))
      val podId = AbsolutePathId("/dev/testing")
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(podId, f.clock.now()).getInstance()
      val pod: PodDefinition = PodDefinition(
        id = podId,
        executorResources = ExecutorResources(disk = 0).fromRaml,
        containers = Seq(
          MesosContainer(
            resources = Resources(cpus = 0.1, mem = 32.0, disk = 0),
            name = "first")),
        role = "*")
      val scheduledInstance = Instance.scheduled(pod, Instance.Id.forRunSpec(podId))

      val request = InstanceOpFactory.Request(
        offer,
        Map(instance.instanceId -> instance),
        scheduledInstances = NonEmptyIterable(scheduledInstance))

      val matched = inside(f.instanceOpFactory.matchOfferRequest(request)) {
        case matched: OfferMatchResult.Match => matched
      }

      val Some(op) = matched.instanceOp.offerOperations.headOption

      val envValue = op.getLaunchGroup.getTaskGroup.getTasks(0).getCommand.getEnvironment.getVariablesList.asScala.collect { case pair if pair.getName == TaskBuilderConstants.MARATHON_APP_ENFORCE_GROUP_ROLE => pair.getValue }
      envValue.head shouldBe "TRUE"
    }

    "enforceRole property is propagated to task environment for apps" in {
      val f = new Fixture
      f.rootGroup = f.rootGroup.putGroup(Group(AbsolutePathId("/dev"), enforceRole = true))
      val appId = AbsolutePathId("/dev/testing")
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(appId, f.clock.now()).getInstance()
      val app: AppDefinition = AppDefinition(id = appId, portDefinitions = List(), role = "*")
      val scheduledInstance = Instance.scheduled(app, Instance.Id.forRunSpec(appId))

      val request = InstanceOpFactory.Request(
        offer,
        Map(instance.instanceId -> instance),
        scheduledInstances = NonEmptyIterable(scheduledInstance))

      val matched = inside(f.instanceOpFactory.matchOfferRequest(request)) {
        case matched: OfferMatchResult.Match => matched
      }

      val Some(op) = matched.instanceOp.offerOperations.headOption

      val envValue = op.getLaunch.getTaskInfos(0).getCommand.getEnvironment.getVariablesList.asScala.collect { case pair if pair.getName == TaskBuilderConstants.MARATHON_APP_ENFORCE_GROUP_ROLE => pair.getValue }
      envValue.head shouldBe "TRUE"
    }
  }

  class Fixture {

    import mesosphere.marathon.test.{MarathonTestHelper => MTH}

    val instanceTracker = mock[InstanceTracker]
    val config: MarathonConf = MTH.defaultConfig(mesosRole = Some("test"))
    implicit val clock = new SettableClock()
    val metrics: Metrics = DummyMetrics
    var rootGroup = RootGroup.empty()
    val enforceRoleSettingProvider: GroupManager.EnforceRoleSettingProvider = new GroupManager.EnforceRoleSettingProvider {
      override def enforceRoleSetting(id: AbsolutePathId): Boolean = rootGroup.group(id).exists(_.enforceRole)
    }

    val instanceOpFactory: InstanceOpFactory = new InstanceOpFactoryImpl(metrics, config, enforceRoleProvider = enforceRoleSettingProvider)
    val defaultHostName = AgentTestDefaults.defaultHostName
    val defaultAgentId = AgentTestDefaults.defaultAgentId

    def normalApp = MTH.makeBasicApp()
    def residentApp = MTH.appWithPersistentVolume().copy(role = "test")

    def scheduledReservedInstance(appId: AbsolutePathId, volumeIds: LocalVolumeId*) =
      TestInstanceBuilder.scheduledWithReservation(residentApp.copy(id = appId), Seq(volumeIds: _*))

    def residentLaunchedInstance(appId: AbsolutePathId, volumeIds: LocalVolumeId*) =
      TestInstanceBuilder.newBuilder(appId).addTaskResidentLaunched(Seq(volumeIds: _*)).getInstance()

    def offer = MTH.makeBasicOffer().build()

    def offerWithSpaceForLocalVolume = MTH.makeBasicOffer(disk = 1025).build()

    def insufficientOffer = MTH.makeBasicOffer(cpus = 0.01, mem = 1, disk = 0.01, beginPort = 31000, endPort = 31001).build()

    def offerWithVolumes(taskId: Task.Id, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, defaultHostName, defaultAgentId, localVolumeIds: _*)

    def offerWithVolumes(taskId: Task.Id, hostname: String, agentId: String, localVolumeIds: LocalVolumeId*) =
      MTH.offerWithVolumes(taskId, hostname, agentId, localVolumeIds: _*)
  }

}
