package mesosphere.marathon
package tasks

import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerModule }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.mockito.Mockito.spy
import org.scalatest.matchers.{ HavePropertyMatchResult, HavePropertyMatcher }

class InstanceTrackerImplTest extends AkkaUnitTest {

  val TEST_APP_NAME = PathId("/foo")

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
      val sampleTask = makeSampleInstance(TEST_APP_NAME)
      val originalInstance: Instance = sampleTask

      instanceTracker.launchEphemeral(originalInstance).futureValue

      val deserializedInstance = instanceTracker.instance(originalInstance.instanceId).futureValue

      deserializedInstance should not be empty
      deserializedInstance should equal(Some(originalInstance))
    }

    "List Async" in new Fixture {
      val instance1 = makeSampleInstance(TEST_APP_NAME / "a")
      val instance2 = makeSampleInstance(TEST_APP_NAME / "b")
      val instance3 = makeSampleInstance(TEST_APP_NAME / "b")

      instanceTracker.launchEphemeral(instance1).futureValue
      instanceTracker.launchEphemeral(instance2).futureValue
      instanceTracker.launchEphemeral(instance3).futureValue

      val testAppTasks = instanceTracker.instancesBySpec().futureValue

      testAppTasks.allSpecIdsWithInstances should be(Set(TEST_APP_NAME / "a", TEST_APP_NAME / "b"))

      testAppTasks.instancesMap(TEST_APP_NAME / "a").instances should have size 1
      testAppTasks.instancesMap(TEST_APP_NAME / "b").instances should have size 2
      testAppTasks.instancesMap(TEST_APP_NAME / "a").instanceMap.keySet should equal(Set(instance1.instanceId))
      testAppTasks.instancesMap(TEST_APP_NAME / "b").instanceMap.keySet should equal(Set(instance2.instanceId, instance3.instanceId))
    }

    "GetTasks Async" in new Fixture {
      val instance1 = makeSampleInstance(TEST_APP_NAME)
      val instance2 = makeSampleInstance(TEST_APP_NAME)
      val instance3 = makeSampleInstance(TEST_APP_NAME)

      instanceTracker.launchEphemeral(instance1).futureValue
      instanceTracker.launchEphemeral(instance2).futureValue
      instanceTracker.launchEphemeral(instance3).futureValue

      val testAppInstances = instanceTracker.specInstances(TEST_APP_NAME).futureValue

      testAppInstances should contain allOf (instance1, instance2, instance3)
      testAppInstances should have size (3)
    }

    "Contains Async" in new Fixture {
      val task1 = makeSampleInstance(TEST_APP_NAME / "a")

      instanceTracker.launchEphemeral(task1).futureValue

      instanceTracker.hasSpecInstances(TEST_APP_NAME / "a").futureValue should be(true)
      instanceTracker.hasSpecInstances(TEST_APP_NAME / "b").futureValue should be(false)
    }

    "TaskLifecycle" in new Fixture {
      val sampleInstance = TestInstanceBuilder.newBuilder(TEST_APP_NAME).addTaskStarting().getInstance()

      // CREATE TASK
      instanceTracker.launchEphemeral(sampleInstance).futureValue

      instanceTracker.specInstances(TEST_APP_NAME).futureValue should contain(sampleInstance)
      state.ids().runWith(Sink.set).futureValue should contain(sampleInstance.instanceId)

      // TASK STATUS UPDATE
      val mesosStatus = makeTaskStatus(sampleInstance, TaskState.TASK_STARTING)

      instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstances(TEST_APP_NAME).futureValue should contain(sampleInstance)
      state.ids().runWith(Sink.set).futureValue should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue) should be('active)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue.flatMap(_.tasksMap.values)) should have(taskStatus(mesosStatus))

      // TASK RUNNING
      val runningStatus = makeTaskStatus(sampleInstance, TaskState.TASK_RUNNING)

      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue

      instanceTracker.specInstances(TEST_APP_NAME).futureValue.map(_.instanceId) should contain(sampleInstance.instanceId)
      state.ids().runWith(Sink.set).futureValue should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue) should be('active)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue.flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK STILL RUNNING
      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue
      instanceTracker.specInstances(TEST_APP_NAME).futureValue.map(_.instanceId) should contain(sampleInstance.instanceId)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue.headOption.toList) should be('active)
      every(instanceTracker.specInstances(TEST_APP_NAME).futureValue.headOption.toList.flatMap(_.tasksMap.values)) should have(taskStatus(runningStatus))

      // TASK TERMINATED
      instanceTracker.forceExpunge(sampleInstance.instanceId).futureValue
      state.ids().runWith(Sink.set).futureValue should not contain (sampleInstance.instanceId)

      // APP SHUTDOWN
      assert(!instanceTracker.hasSpecInstances(TEST_APP_NAME).futureValue, "App was not removed")

      // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
      val lostStatus = makeTaskStatus(sampleInstance, TaskState.TASK_LOST)

      val failure = instanceTracker.updateStatus(sampleInstance, lostStatus, clock.now()).failed.futureValue
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
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)
      val mesosStatus = makeTaskStatus(sampleInstance, taskState)

      instanceTracker.launchEphemeral(sampleInstance).futureValue
      instanceTracker.specInstances(TEST_APP_NAME).futureValue should contain(sampleInstance)
      state.ids().runWith(Sink.set).futureValue should contain(sampleInstance.instanceId)

      instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now()).futureValue

      instanceTracker.specInstances(TEST_APP_NAME).futureValue should not contain (sampleInstance)
      state.ids().runWith(Sink.set).futureValue should not contain (sampleInstance.instanceId)
    }

    "UnknownTasks" in new Fixture {
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)

      // don't call taskTracker.created, but directly running
      val mesosStatus = makeTaskStatus(sampleInstance, TaskState.TASK_RUNNING)
      val res = instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now())
      res.failed.futureValue.getCause.getMessage should equal(s"${sampleInstance.instanceId} of app [/foo] does not exist")

      instanceTracker.specInstances(TEST_APP_NAME).futureValue should not contain (sampleInstance)
      state.ids().runWith(Sink.set).futureValue should not contain (sampleInstance.instanceId)
    }

    "MultipleApps" in new Fixture {
      val appName1 = "app1".toRootPath
      val appName2 = "app2".toRootPath
      val appName3 = "app3".toRootPath

      val app1_instance1 = makeSampleInstance(appName1)
      val app1_instance2 = makeSampleInstance(appName1)
      val app2_instance1 = makeSampleInstance(appName2)
      val app3_instance1 = makeSampleInstance(appName3)
      val app3_instance2 = makeSampleInstance(appName3)
      val app3_instance3 = makeSampleInstance(appName3)

      instanceTracker.launchEphemeral(app1_instance1).futureValue
      instanceTracker.updateStatus(app1_instance1, makeTaskStatus(app1_instance1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app1_instance2).futureValue
      instanceTracker.updateStatus(app1_instance2, makeTaskStatus(app1_instance2, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app2_instance1).futureValue
      instanceTracker.updateStatus(app2_instance1, makeTaskStatus(app2_instance1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_instance1).futureValue
      instanceTracker.updateStatus(app3_instance1, makeTaskStatus(app3_instance1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_instance2).futureValue
      instanceTracker.updateStatus(app3_instance2, makeTaskStatus(app3_instance2, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_instance3).futureValue
      instanceTracker.updateStatus(app3_instance3, makeTaskStatus(app3_instance3, TaskState.TASK_RUNNING), clock.now()).futureValue

      state.ids().runWith(Sink.seq).futureValue should have size (6)

      val app1Instances = instanceTracker.specInstances(appName1).futureValue

      app1Instances.map(_.instanceId) should contain allOf (app1_instance1.instanceId, app1_instance2.instanceId)
      app1Instances should have size (2)

      val app2Instances = instanceTracker.specInstances(appName2).futureValue

      app2Instances.map(_.instanceId) should contain(app2_instance1.instanceId)
      app2Instances should have size (1)

      val app3Instances = instanceTracker.specInstances(appName3).futureValue

      app3Instances.map(_.instanceId) should contain allOf (app3_instance1.instanceId, app3_instance2.instanceId, app3_instance3.instanceId)
      app3Instances should have size (3)
    }

    "Should not store if state did not change (no health present)" in new Fixture {
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should not store if state and health did not change" in new Fixture {
      val sampleInstance = TestInstanceBuilder.newBuilder(TEST_APP_NAME).addTaskWithBuilder().taskRunning().asHealthyTask().build().getInstance()
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get
        .toBuilder
        .setTimestamp(123)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      verify(state, times(0)).store(any)
    }

    "Should store if state changed" in new Fixture {
      val sampleInstance = TestInstanceBuilder.newBuilder(TEST_APP_NAME).addTaskStaged().getInstance()
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get.toBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      instanceTracker.updateStatus(sampleInstance, status, clock.now()).futureValue

      reset(state)

      val newStatus = status.toBuilder
        .setState(Protos.TaskState.TASK_FAILED)
        .build()

      instanceTracker.updateStatus(sampleInstance, newStatus, clock.now()).futureValue

      verify(state, times(1)).delete(any)
    }

    "Should store if health changed" in new Fixture {
      val sampleInstance = TestInstanceBuilder.newBuilder(TEST_APP_NAME).addTaskRunning().getInstance()
      val (_, task) = sampleInstance.tasksMap.head
      val status = task.status.mesosStatus.get.toBuilder
        .setHealthy(true)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
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
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id.forInstanceId(sampleInstance.instanceId, None).mesosTaskId)
        .setHealthy(true)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
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
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id.forInstanceId(sampleInstance.instanceId, None).mesosTaskId)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
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
      val sampleInstance = makeSampleInstance(TEST_APP_NAME)
      val status = Protos.TaskStatus
        .newBuilder
        .setState(Protos.TaskState.TASK_RUNNING)
        .setTaskId(Task.Id.forInstanceId(sampleInstance.instanceId, None).mesosTaskId)
        .build()

      instanceTracker.launchEphemeral(sampleInstance).futureValue
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

  def makeTaskStatus(instance: Instance, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(Task.Id.forInstanceId(instance.instanceId, None).mesosTaskId)
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
