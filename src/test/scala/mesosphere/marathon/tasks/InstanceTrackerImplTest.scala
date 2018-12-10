package mesosphere.marathon
package tasks

import akka.stream.scaladsl.Sink
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.Schedule
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerModule}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp, VersionInfo}
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.stream.EnrichedSink
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.apache.mesos
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{TaskState, TaskStatus}
import org.mockito.Mockito.spy
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import scala.async.Async.{async, await}
import scala.concurrent.Future

class InstanceTrackerImplTest extends AkkaUnitTest {

  val TEST_APP_NAME = PathId("/foo")

  case class Fixture() {
    val metrics: Metrics = DummyMetrics
    val store: InMemoryPersistenceStore = {
      val store = new InMemoryPersistenceStore(metrics)
      store.markOpen()
      store
    }
    implicit val state: InstanceRepository = spy(InstanceRepository.inMemRepository(store))
    val config: AllConf = MarathonTestHelper.defaultConfig()
    implicit val clock: SettableClock = new SettableClock()
    val taskTrackerModule: InstanceTrackerModule = MarathonTestHelper.createTaskTrackerModule(
      AlwaysElectedLeadershipModule.forRefFactory(system), Some(state))
    implicit val instanceTracker: InstanceTracker = taskTrackerModule.instanceTracker
  }

  "InstanceTrackerImpl" should {
    "SerializeAndDeserialize" in new Fixture {
      val originalInstance: Instance = Instance.scheduled(AppDefinition(TEST_APP_NAME))
      instanceTracker.process(Schedule(originalInstance)).futureValue

      val deserializedInstance = instanceTracker.instance(originalInstance.instanceId).futureValue

      deserializedInstance should equal(Some(originalInstance))
    }

    "List" in new Fixture {
      testList(_.instancesBySpecSync)
    }

    "List Async" in new Fixture {
      testList(_.instancesBySpec().futureValue)
    }

    def testList(call: InstanceTracker => InstanceTracker.InstancesBySpec)(implicit instanceTracker: InstanceTracker): Unit = {
      val instance1 = Instance.scheduled(AppDefinition(TEST_APP_NAME / "a"))
      instanceTracker.process(Schedule(instance1)).futureValue
      val instance2 = Instance.scheduled(AppDefinition(TEST_APP_NAME / "b"))
      instanceTracker.process(Schedule(instance2)).futureValue
      val instance3 = Instance.scheduled(AppDefinition(TEST_APP_NAME / "b"))
      instanceTracker.process(Schedule(instance3)).futureValue

      val testAppTasks = call(instanceTracker)

      testAppTasks.allSpecIdsWithInstances should be(Set(TEST_APP_NAME / "a", TEST_APP_NAME / "b"))

      testAppTasks.instancesMap(TEST_APP_NAME / "a").instances should have size 1
      testAppTasks.instancesMap(TEST_APP_NAME / "b").instances should have size 2
      testAppTasks.instancesMap(TEST_APP_NAME / "a").instanceMap.keySet should equal(Set(instance1.instanceId))
      testAppTasks.instancesMap(TEST_APP_NAME / "b").instanceMap.keySet should equal(Set(instance2.instanceId, instance3.instanceId))
    }

    "GetTasks" in new Fixture {
      testGetTasks(_.specInstancesSync(TEST_APP_NAME))
    }

    "GetTasks Async" in new Fixture {
      testGetTasks(_.specInstances(TEST_APP_NAME).futureValue)
    }

    def testGetTasks(call: InstanceTracker => Seq[Instance])(implicit instanceTracker: InstanceTracker): Unit = {
      val instance1 = Instance.scheduled(AppDefinition(TEST_APP_NAME))
      instanceTracker.process(Schedule(instance1)).futureValue
      val instance2 = Instance.scheduled(AppDefinition(TEST_APP_NAME))
      instanceTracker.process(Schedule(instance2)).futureValue
      val instance3 = Instance.scheduled(AppDefinition(TEST_APP_NAME))
      instanceTracker.process(Schedule(instance3)).futureValue

      val testAppInstances = call(instanceTracker)

      testAppInstances should contain allOf (instance1, instance2, instance3)
      testAppInstances should have size (3)
    }

    "Contains" in new Fixture {
      testContains(_.hasSpecInstancesSync(_))
    }

    "Contains Async" in new Fixture {
      testContains(_.hasSpecInstances(_).futureValue)
    }

    def testContains(count: (InstanceTracker, PathId) => Boolean)(implicit instanceTracker: InstanceTracker): Unit = {
      val task1 = Instance.scheduled(AppDefinition(TEST_APP_NAME / "a"))
      instanceTracker.process(Schedule(task1)).futureValue

      count(instanceTracker, TEST_APP_NAME / "a") should be(true)
      count(instanceTracker, TEST_APP_NAME / "b") should be(false)
    }

    "TaskLifecycle" in new Fixture {
      val sampleInstance = setupTrackerWithProvisionedInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue

      instanceTracker.specInstancesSync(TEST_APP_NAME) should contain(sampleInstance)
      state.ids().runWith(EnrichedSink.set).futureValue should contain(sampleInstance.instanceId)

      // TASK STATUS UPDATE
      val mesosStatus = makeTaskStatus(sampleInstance, TaskState.TASK_STARTING)

      instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(TEST_APP_NAME).head.instanceId should be(sampleInstance.instanceId)
      state.ids().runWith(EnrichedSink.set).futureValue should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME)) should be('active)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME).flatMap(_.tasksMap.values)) should have(taskStatus(mesosStatus))

      // TASK RUNNING
      val runningStatus = makeTaskStatus(sampleInstance, TaskState.TASK_RUNNING)

      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(TEST_APP_NAME).map(_.instanceId) should contain(sampleInstance.instanceId)
      state.ids().runWith(EnrichedSink.set).futureValue should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME)) should be('active)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME).flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK STILL RUNNING
      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue
      instanceTracker.specInstancesSync(TEST_APP_NAME).map(_.instanceId) should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME).headOption.toList) should be('active)
      every(instanceTracker.specInstancesSync(TEST_APP_NAME).headOption.toList.flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK TERMINATED
      instanceTracker.forceExpunge(sampleInstance.instanceId).futureValue
      state.ids().runWith(EnrichedSink.set).futureValue should not contain (sampleInstance.instanceId)

      // APP SHUTDOWN
      assert(!instanceTracker.hasSpecInstancesSync(TEST_APP_NAME), "App was not removed")

      // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
      val lostStatus = makeTaskStatus(sampleInstance, TaskState.TASK_LOST)

      val failure = instanceTracker.updateStatus(sampleInstance, lostStatus, clock.now()).failed.futureValue
      assert(failure != null)
      assert(failure.getMessage.contains("does not exist"), s"message: ${failure.getMessage}")
    }

    "TASK_FAILED status update will expunge task" in new Fixture {
      testStatusUpdateForTerminalState(TaskState.TASK_FAILED)
    }
    "TASK_FINISHED status update will expunge task" in new Fixture {
      testStatusUpdateForTerminalState(TaskState.TASK_FINISHED)
    }
    "TASK_LOST status update will expunge task" in new Fixture {
      testStatusUpdateForTerminalState(TaskState.TASK_LOST)
    }
    "TASK_KILLED status update will expunge task" in new Fixture {
      testStatusUpdateForTerminalState(TaskState.TASK_KILLED)
    }
    "TASK_ERROR status update will expunge task" in new Fixture {
      testStatusUpdateForTerminalState(TaskState.TASK_ERROR)
    }

    def testStatusUpdateForTerminalState(taskState: TaskState)(implicit instanceTracker: InstanceTracker, clock: SettableClock, state: InstanceRepository): Unit = {
      val sampleInstance = setupTrackerWithProvisionedInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val mesosStatus = makeTaskStatus(sampleInstance, taskState)

      instanceTracker.specInstancesSync(TEST_APP_NAME) should contain(sampleInstance)
      state.ids().runWith(EnrichedSink.set).futureValue should contain(sampleInstance.instanceId)

      instanceTracker.setGoal(sampleInstance.instanceId, Goal.Decommissioned)
      instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(TEST_APP_NAME) should not contain (sampleInstance)
      state.ids().runWith(EnrichedSink.set).futureValue should not contain (sampleInstance.instanceId)
    }

    "UnknownTasks" in new Fixture {
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)

      // don't call taskTracker.created, but directly running
      val mesosStatus = makeTaskStatus(sampleInstance, TaskState.TASK_RUNNING)
      val res = instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now())
      res.failed.futureValue.getMessage should equal(s"${sampleInstance.instanceId} of app [/foo] does not exist")

      instanceTracker.specInstancesSync(TEST_APP_NAME) should not contain (sampleInstance)
      state.ids().runWith(EnrichedSink.set).futureValue should not contain (sampleInstance.instanceId)
    }

    "MultipleApps" in new Fixture {
      val appName1 = "app1".toRootPath
      val appName2 = "app2".toRootPath
      val appName3 = "app3".toRootPath

      val app1_instance1 = setupTrackerWithRunningInstance(appName1, Timestamp.now(), instanceTracker).futureValue
      val app1_instance2 = setupTrackerWithRunningInstance(appName1, Timestamp.now(), instanceTracker).futureValue
      val app2_instance1 = setupTrackerWithRunningInstance(appName2, Timestamp.now(), instanceTracker).futureValue
      val app3_instance1 = setupTrackerWithRunningInstance(appName3, Timestamp.now(), instanceTracker).futureValue
      val app3_instance2 = setupTrackerWithRunningInstance(appName3, Timestamp.now(), instanceTracker).futureValue
      val app3_instance3 = setupTrackerWithRunningInstance(appName3, Timestamp.now(), instanceTracker).futureValue

      state.ids().runWith(Sink.seq).futureValue should have size (6)

      val app1Instances = instanceTracker.specInstancesSync(appName1)

      app1Instances.map(_.instanceId) should contain allOf (app1_instance1.instanceId, app1_instance2.instanceId)
      app1Instances should have size (2)

      val app2Instances = instanceTracker.specInstancesSync(appName2)

      app2Instances.map(_.instanceId) should contain(app2_instance1.instanceId)
      app2Instances should have size (1)

      val app3Instances = instanceTracker.specInstancesSync(appName3)

      app3Instances.map(_.instanceId) should contain allOf (app3_instance1.instanceId, app3_instance2.instanceId, app3_instance3.instanceId)
      app3Instances should have size (3)
    }

    "Should not store if state did not change (no health present)" in new Fixture {
      val sampleInstance = setupTrackerWithRunningInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should not store if state and health did not change" in new Fixture {
      val sampleInstance = setupTrackerWithRunningInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should store if state changed" in new Fixture {
      val sampleInstance = setupTrackerWithRunningInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      instanceTracker.setGoal(sampleInstance.instanceId, Goal.Decommissioned)

      reset(state)

      val (_, task) = sampleInstance.tasksMap.head
      val newStatus = task.status.mesosStatus.get.toBuilder
        .setState(Protos.TaskState.TASK_FAILED)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).delete(any)
    }

    "Should store if health changed" in new Fixture {
      val sampleInstance = setupTrackerWithRunningInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get.toBuilder
        .setHealthy(true)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if state and health changed" in new Fixture {
      val sampleInstance = setupTrackerWithProvisionedInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id(sampleInstance.instanceId).mesosTaskId)
        .setHealthy(true)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if health changed (no health present at first)" in new Fixture {
      val sampleInstance = setupTrackerWithProvisionedInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id(sampleInstance.instanceId).mesosTaskId)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setHealthy(true)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if state and health changed (no health present at first)" in new Fixture {
      val sampleInstance = setupTrackerWithProvisionedInstance(TEST_APP_NAME, Timestamp.now(), instanceTracker).futureValue
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id(sampleInstance.instanceId).mesosTaskId)
        .build()

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }
  }

  def makeSampleInstance(appId: PathId): Instance = {
    val hostName = "host"
    TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskStaged()
      .withNetworkInfo(hostName = Some(hostName), hostPorts = Seq(999))
      .build()
      .withAgentInfo(hostName = Some(hostName), attributes = Some(Seq(TextAttribute("attr1", "bar"))))
      .getInstance()
  }

  def setupTrackerWithProvisionedInstance(appId: PathId, version: Timestamp, instanceTracker: InstanceTracker): Future[Instance] = async {
    val app = AppDefinition(appId, versionInfo = VersionInfo.OnlyVersion(version))
    val scheduledInstance = Instance.scheduled(app)
    // schedule
    await(instanceTracker.schedule(scheduledInstance))
    // provision
    val updateEffect = await(instanceTracker.process(
      InstanceUpdateOperation.Provision(
        scheduledInstance.instanceId,
        AgentInfoPlaceholder(),
        app,
        Tasks.provisioned(Task.Id(scheduledInstance.instanceId), NetworkInfoPlaceholder(), app.version, Timestamp.now()),
        Timestamp.now()))
    ).asInstanceOf[InstanceUpdateEffect.Update]

    updateEffect.instance
  }

  def setupTrackerWithRunningInstance(appId: PathId, version: Timestamp, instanceTracker: InstanceTracker): Future[Instance] = async {
    val instance: Instance = await(setupTrackerWithProvisionedInstance(appId, version, instanceTracker))
    val (taskId, _) = instance.tasksMap.head
    // update to running
    val taskStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(mesos.Protos.TaskState.TASK_RUNNING)
      .setHealthy(true)
      .build
    await(instanceTracker.updateStatus(instance, taskStatus, Timestamp.now()))
    await(instanceTracker.get(instance.instanceId).map(_.get))
  }

  def makeTaskStatus(instance: Instance, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(Task.Id(instance.instanceId).mesosTaskId)
      .setState(state)
      .build
  }

  class TaskStatusMatcher(expectedStatus: Protos.TaskStatus) extends HavePropertyMatcher[Task, Option[Protos.TaskStatus]] {
    def apply(task: Task): HavePropertyMatchResult[Option[Protos.TaskStatus]] = {
      val matches = task.status.mesosStatus.contains(expectedStatus)
      HavePropertyMatchResult(matches, "status", Some(expectedStatus), task.status.mesosStatus)
    }
  }

  def taskStatus(expectedStatus: Protos.TaskStatus) = new TaskStatusMatcher(expectedStatus)

  def stateShouldNotContainKey(state: InstanceRepository, key: Instance.Id): Unit = {
    assert(!state.ids().runWith(EnrichedSink.set).futureValue.contains(key), s"Key $key was found in state")
  }

  def stateShouldContainKey(state: InstanceRepository, key: Instance.Id): Unit = {
    assert(state.ids().runWith(EnrichedSink.set).futureValue.contains(key), s"Key $key was not found in state")
  }
}
