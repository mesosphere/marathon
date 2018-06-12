package mesosphere.marathon
package tasks

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.{AgentInfoPlaceholder, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerModule}
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{TaskState, TaskStatus}
import org.mockito.Mockito.spy
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

class InstanceTrackerImplTest extends AkkaUnitTest {

  val testAppId = PathId("/foo")
  val testApp = AppDefinition(testAppId)

  case class Fixture() {
    val store: InMemoryPersistenceStore = {
      val store = new InMemoryPersistenceStore()
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
      val originalInstance: Instance = Instance.Scheduled(testApp)

      instanceTracker.schedule(originalInstance).futureValue

      val deserializedInstance = instanceTracker.instance(originalInstance.instanceId).futureValue

      deserializedInstance should not be empty
      deserializedInstance should equal(Some(originalInstance))
    }

    "List" in new Fixture {
      testList(_.instancesBySpecSync)
    }

    "List Async" in new Fixture {
      testList(_.instancesBySpec().futureValue)
    }

    def testList(call: InstanceTracker => InstanceTracker.InstancesBySpec)(implicit instanceTracker: InstanceTracker): Unit = {
      val instance1 = Instance.Scheduled(AppDefinition(testAppId / "a"))
      val instance2 = Instance.Scheduled(AppDefinition(testAppId / "b"))
      val instance3 = Instance.Scheduled(AppDefinition(testAppId / "b"))

      instanceTracker.schedule(instance1).futureValue
      instanceTracker.schedule(instance2).futureValue
      instanceTracker.schedule(instance3).futureValue

      val testAppTasks = call(instanceTracker)

      testAppTasks.allSpecIdsWithInstances should be(Set(testAppId / "a", testAppId / "b"))

      testAppTasks.instancesMap(testAppId / "a").instances should have size 1
      testAppTasks.instancesMap(testAppId / "b").instances should have size 2
      testAppTasks.instancesMap(testAppId / "a").instanceMap.keySet should equal(Set(instance1.instanceId))
      testAppTasks.instancesMap(testAppId / "b").instanceMap.keySet should equal(Set(instance2.instanceId, instance3.instanceId))
    }

    "GetTasks" in new Fixture {
      testGetInstances(_.specInstancesSync(testAppId))
    }

    "GetTasks Async" in new Fixture {
      testGetInstances(_.specInstances(testAppId).futureValue)
    }

    def testGetInstances(call: InstanceTracker => Seq[Instance])(implicit instanceTracker: InstanceTracker): Unit = {
      val instance1 = Instance.Scheduled(testApp)
      val instance2 = Instance.Scheduled(testApp)
      val instance3 = Instance.Scheduled(testApp)

      instanceTracker.schedule(instance1).futureValue
      instanceTracker.schedule(instance2).futureValue
      instanceTracker.schedule(instance3).futureValue

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
      val scheduledInstance = Instance.Scheduled(AppDefinition(testAppId / "a"))

      instanceTracker.schedule(scheduledInstance).futureValue

      count(instanceTracker, testAppId / "a") should be(true)
      count(instanceTracker, testAppId / "b") should be(false)
    }

    "TaskLifecycle" in new Fixture {
      val scheduledInstance = Instance.Scheduled(testApp)

      // Schedule
      instanceTracker.schedule(scheduledInstance).futureValue

      // Provision
      val provisionedInstance = Instance.Provisioned(scheduledInstance, AgentInfoPlaceholder(), NetworkInfoPlaceholder(), testApp, Timestamp.now())
      instanceTracker.process(InstanceUpdateOperation.Provision(provisionedInstance)).futureValue

      instanceTracker.specInstancesSync(testAppId) should contain(provisionedInstance)
      state.ids().runWith(Sink.set).futureValue should contain(provisionedInstance.instanceId)

      // TASK STATUS UPDATE
      val mesosStatus = makeTaskStatus(provisionedInstance.instanceId, TaskState.TASK_STARTING)

      instanceTracker.updateStatus(provisionedInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(testAppId).map(_.instanceId) should contain(provisionedInstance.instanceId)
      state.ids().runWith(Sink.set).futureValue should contain(provisionedInstance.instanceId)
      every(instanceTracker.specInstancesSync(testAppId)) should be('active)
      every(instanceTracker.specInstancesSync(testAppId).flatMap(_.tasksMap.values)) should have(taskStatus(mesosStatus))

      // TASK RUNNING
      val runningStatus = makeTaskStatus(provisionedInstance.instanceId, TaskState.TASK_RUNNING)

      instanceTracker.updateStatus(provisionedInstance, runningStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(testAppId).map(_.instanceId) should contain(provisionedInstance.instanceId)
      state.ids().runWith(Sink.set).futureValue should contain(provisionedInstance.instanceId)
      every(instanceTracker.specInstancesSync(testAppId)) should be('active)
      every(instanceTracker.specInstancesSync(testAppId).flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK STILL RUNNING
      instanceTracker.updateStatus(provisionedInstance, runningStatus, clock.now()).futureValue
      instanceTracker.specInstancesSync(testAppId).map(_.instanceId) should contain(provisionedInstance.instanceId)
      every(instanceTracker.specInstancesSync(testAppId).headOption.toList) should be('active)
      every(instanceTracker.specInstancesSync(testAppId).headOption.toList.flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK TERMINATED
      instanceTracker.forceExpunge(provisionedInstance.instanceId).futureValue
      state.ids().runWith(Sink.set).futureValue should not contain (provisionedInstance.instanceId)

      // APP SHUTDOWN
      assert(!instanceTracker.hasSpecInstancesSync(testAppId), "App was not removed")

      // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
      val lostStatus = makeTaskStatus(provisionedInstance.instanceId, TaskState.TASK_LOST)

      val failure = instanceTracker.updateStatus(provisionedInstance, lostStatus, clock.now()).failed.futureValue
      assert(failure.getCause != null)
      assert(failure.getCause.getMessage.contains("does not exist"), s"message: ${failure.getMessage}")
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
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val runningInstance = instanceTracker.instance(instanceId).futureValue.get

      val mesosStatus = makeTaskStatus(instanceId, taskState)
      instanceTracker.updateStatus(runningInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstancesSync(testAppId).map(_.instanceId) should not contain (instanceId)
      state.ids().runWith(Sink.set).futureValue should not contain (runningInstance.instanceId)
    }

    "UnknownTasks" in new Fixture {
      val sampleInstance = makeSampleInstance(testAppId)

      // don't call taskTracker.created, but directly running
      val mesosStatus = makeTaskStatus(sampleInstance.instanceId, TaskState.TASK_RUNNING)
      val res = instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now())
      res.failed.futureValue.getCause.getMessage should equal(s"${sampleInstance.instanceId} of app [/foo] does not exist")

      instanceTracker.specInstancesSync(testAppId) should not contain (sampleInstance)
      state.ids().runWith(Sink.set).futureValue should not contain (sampleInstance.instanceId)
    }

    "MultipleApps" in new Fixture {
      val appName1 = "app1".toRootPath
      val appName2 = "app2".toRootPath
      val appName3 = "app3".toRootPath

      val (i1Id, _) = TestInstanceBuilder.runningInstance(appName1, Timestamp.zero, instanceTracker).futureValue
      val (i2Id, _) = TestInstanceBuilder.runningInstance(appName1, Timestamp.zero, instanceTracker).futureValue

      val (i3Id, _) = TestInstanceBuilder.runningInstance(appName2, Timestamp.zero, instanceTracker).futureValue

      val (i4Id, _) = TestInstanceBuilder.runningInstance(appName3, Timestamp.zero, instanceTracker).futureValue
      val (i5Id, _) = TestInstanceBuilder.runningInstance(appName3, Timestamp.zero, instanceTracker).futureValue
      val (i6Id, _) = TestInstanceBuilder.runningInstance(appName3, Timestamp.zero, instanceTracker).futureValue

      state.ids().runWith(Sink.seq).futureValue should have size (6)

      val app1Instances = instanceTracker.specInstancesSync(appName1)

      app1Instances.map(_.instanceId) should contain allOf (i1Id, i2Id)
      app1Instances should have size (2)

      val app2Instances = instanceTracker.specInstancesSync(appName2)

      app2Instances.map(_.instanceId) should contain(i3Id)
      app2Instances should have size (1)

      val app3Instances = instanceTracker.specInstancesSync(appName3)

      app3Instances.map(_.instanceId) should contain allOf (i4Id, i5Id, i6Id)
      app3Instances should have size (3)
    }

    "Should not store if state did not change (no health present)" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val (_, task) = instance.tasksMap.head
      val status = task.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .build()

      instanceTracker.updateStatus(instance, status, clock.now()).futureValue
      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should not store if state and health did not change" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val status = instance.appTask.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .setHealthy(true)
        .build()
      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should store if state changed" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get

      reset(state)

      val newStatus = instance.appTask.status.mesosStatus.get.toBuilder
        .setState(Protos.TaskState.TASK_FAILED)
        .build()

      instanceTracker.updateStatus(instance, newStatus, clock.now()).futureValue

      verify(state, times(1)).delete(any)
    }

    "Should store if health changed" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val status = instance.appTask.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .setHealthy(true)
        .build()
      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(instance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if state and health changed" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val status = instance.appTask.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .setHealthy(true)
        .build()
      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(instance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if health changed (no health present at first)" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id.forInstanceId(instance.instanceId, None).mesosTaskId)
        .build()

      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setHealthy(true)
        .build()

      instanceTracker.updateStatus(instance, newStatus, clock.now()).futureValue

      verify(state, times(1)).store(any)
    }

    "Should store if state and health changed (no health present at first)" in new Fixture {
      val (instanceId, _) = TestInstanceBuilder.runningInstance(testAppId, Timestamp.zero, instanceTracker).futureValue
      val instance = instanceTracker.instance(instanceId).futureValue.get
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id.forInstanceId(instance.instanceId, None).mesosTaskId)
        .build()

      instanceTracker.updateStatus(instance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setHealthy(false)
        .build()

      instanceTracker.updateStatus(instance, newStatus, clock.now()).futureValue

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

  def makeTaskStatus(instanceId: Instance.Id, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(Task.Id.forInstanceId(instanceId, None).mesosTaskId)
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
    assert(!state.ids().runWith(Sink.set).futureValue.contains(key), s"Key $key was found in state")
  }

  def stateShouldContainKey(state: InstanceRepository, key: Instance.Id): Unit = {
    assert(state.ids().runWith(Sink.set).futureValue.contains(key), s"Key $key was not found in state")
  }
}
