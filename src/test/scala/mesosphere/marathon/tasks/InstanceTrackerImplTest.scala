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

    "List" in new Fixture {
      testList(_.instancesBySpecSync)
    }

    "List Async" in new Fixture {
      testList(_.instancesBySpec().futureValue)
    }

    def testList(call: InstanceTracker => InstanceTracker.InstancesBySpec)(implicit instanceTracker: InstanceTracker): Unit = {
      val instance1 = makeSampleInstance(TEST_APP_NAME / "a")
      val instance2 = makeSampleInstance(TEST_APP_NAME / "b")
      val instance3 = makeSampleInstance(TEST_APP_NAME / "b")

      instanceTracker.launchEphemeral(instance1).futureValue
      instanceTracker.launchEphemeral(instance2).futureValue
      instanceTracker.launchEphemeral(instance3).futureValue

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
      val instance1 = makeSampleInstance(TEST_APP_NAME)
      val instance2 = makeSampleInstance(TEST_APP_NAME)
      val instance3 = makeSampleInstance(TEST_APP_NAME)

      instanceTracker.launchEphemeral(instance1).futureValue
      instanceTracker.launchEphemeral(instance2).futureValue
      instanceTracker.launchEphemeral(instance3).futureValue

      val testAppTasks = call(instanceTracker)

      shouldContainTask(testAppTasks, instance1)
      shouldContainTask(testAppTasks, instance2)
      shouldContainTask(testAppTasks, instance3)
      assert(testAppTasks.size == 3)
    }

    "Contains" in new Fixture {
      testContains(_.hasSpecInstancesSync(_))
    }

    "Contains Async" in new Fixture {
      testContains(_.hasSpecInstances(_).futureValue)
    }

    def testContains(count: (InstanceTracker, PathId) => Boolean)(implicit instanceTracker: InstanceTracker): Unit = {
      val task1 = makeSampleInstance(TEST_APP_NAME / "a")

      instanceTracker.launchEphemeral(task1).futureValue

      count(instanceTracker, TEST_APP_NAME / "a") should be(true)
      count(instanceTracker, TEST_APP_NAME / "b") should be(false)
    }

    "TaskLifecycle" in new Fixture {
      val sampleInstance = TestInstanceBuilder.newBuilder(TEST_APP_NAME).addTaskStarting().getInstance()

      // CREATE TASK
      instanceTracker.launchEphemeral(sampleInstance).futureValue

      shouldContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleInstance)
      stateShouldContainKey(state, sampleInstance.instanceId)

      // TASK STATUS UPDATE
      val mesosStatus = makeTaskStatus(sampleInstance, TaskState.TASK_STARTING)

      instanceTracker.updateStatus(sampleInstance, mesosStatus, clock.now()).futureValue

      shouldContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleInstance)
      stateShouldContainKey(state, sampleInstance.instanceId)
      instanceTracker.specInstancesSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, mesosStatus))

      // TASK RUNNING
      val runningStatus = makeTaskStatus(sampleInstance, TaskState.TASK_RUNNING)

      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue

      shouldContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleInstance)
      stateShouldContainKey(state, sampleInstance.instanceId)
      instanceTracker.specInstancesSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningStatus))

      // TASK STILL RUNNING
      instanceTracker.updateStatus(sampleInstance, runningStatus, clock.now()).futureValue
      shouldContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleInstance)
      instanceTracker.specInstancesSync(TEST_APP_NAME).headOption.foreach(task =>
        shouldHaveTaskStatus(task, runningStatus))

      // TASK TERMINATED
      instanceTracker.forceExpunge(sampleInstance.instanceId).futureValue
      stateShouldNotContainKey(state, sampleInstance.instanceId)

      // APP SHUTDOWN
      assert(!instanceTracker.hasSpecInstancesSync(TEST_APP_NAME), "App was not removed")

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
      val sampleTask = makeSampleInstance(TEST_APP_NAME)
      val mesosStatus = makeTaskStatus(sampleTask, taskState)

      instanceTracker.launchEphemeral(sampleTask).futureValue
      shouldContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
      stateShouldContainKey(state, sampleTask.instanceId)

      instanceTracker.updateStatus(sampleTask, mesosStatus, clock.now()).futureValue

      shouldNotContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
      stateShouldNotContainKey(state, sampleTask.instanceId)
    }

    "UnknownTasks" in new Fixture {
      val sampleTask = makeSampleInstance(TEST_APP_NAME)

      // don't call taskTracker.created, but directly running
      val mesosStatus = makeTaskStatus(sampleTask, TaskState.TASK_RUNNING)
      val res = instanceTracker.updateStatus(sampleTask, mesosStatus, clock.now())
      res.failed.futureValue.getCause.getMessage should equal(s"${sampleTask.instanceId} of app [/foo] does not exist")

      shouldNotContainTask(instanceTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
      stateShouldNotContainKey(state, sampleTask.instanceId)
    }

    "MultipleApps" in new Fixture {
      val appName1 = "app1".toRootPath
      val appName2 = "app2".toRootPath
      val appName3 = "app3".toRootPath

      val app1_task1 = makeSampleInstance(appName1)
      val app1_task2 = makeSampleInstance(appName1)
      val app2_task1 = makeSampleInstance(appName2)
      val app3_task1 = makeSampleInstance(appName3)
      val app3_task2 = makeSampleInstance(appName3)
      val app3_task3 = makeSampleInstance(appName3)

      instanceTracker.launchEphemeral(app1_task1).futureValue
      instanceTracker.updateStatus(app1_task1, makeTaskStatus(app1_task1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app1_task2).futureValue
      instanceTracker.updateStatus(app1_task2, makeTaskStatus(app1_task2, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app2_task1).futureValue
      instanceTracker.updateStatus(app2_task1, makeTaskStatus(app2_task1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_task1).futureValue
      instanceTracker.updateStatus(app3_task1, makeTaskStatus(app3_task1, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_task2).futureValue
      instanceTracker.updateStatus(app3_task2, makeTaskStatus(app3_task2, TaskState.TASK_RUNNING), clock.now()).futureValue

      instanceTracker.launchEphemeral(app3_task3).futureValue
      instanceTracker.updateStatus(app3_task3, makeTaskStatus(app3_task3, TaskState.TASK_RUNNING), clock.now()).futureValue

      assert(state.ids().runWith(Sink.seq).futureValue.size == 6, "Incorrect number of tasks in state")

      val app1Tasks = instanceTracker.specInstancesSync(appName1)

      shouldContainTask(app1Tasks, app1_task1)
      shouldContainTask(app1Tasks, app1_task2)
      assert(app1Tasks.size == 2, "Incorrect number of tasks")

      val app2Tasks = instanceTracker.specInstancesSync(appName2)

      shouldContainTask(app2Tasks, app2_task1)
      assert(app2Tasks.size == 1, "Incorrect number of tasks")

      val app3Tasks = instanceTracker.specInstancesSync(appName3)

      shouldContainTask(app3Tasks, app3_task1)
      shouldContainTask(app3Tasks, app3_task2)
      shouldContainTask(app3Tasks, app3_task3)
      assert(app3Tasks.size == 3, "Incorrect number of tasks")
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

  def containsTask(tasks: Seq[Instance], task: Instance) =
    tasks.exists(t => t.instanceId == task.instanceId
      && t.agentInfo.host == task.agentInfo.host
      && t.tasksMap.values.flatMap(_.status.networkInfo.hostPorts) == task.tasksMap.values.flatMap(_.status.networkInfo.hostPorts))
  def shouldContainTask(tasks: Seq[Instance], task: Instance) =
    assert(containsTask(tasks, task), s"Should contain ${task.instanceId}")
  def shouldNotContainTask(tasks: Seq[Instance], task: Instance) =
    assert(!containsTask(tasks, task), s"Should not contain ${task.instanceId}")

  def shouldHaveTaskStatus(task: Instance, mesosStatus: Protos.TaskStatus): Unit = {
    assert(Option(mesosStatus).isDefined, "mesos status is None")
    assert(task.isActive)
    assert(
      task.tasksMap.values.map(_.status.mesosStatus.get).forall(status => status == mesosStatus),
      s"Should have task status ${mesosStatus}")
  }

  def stateShouldNotContainKey(state: InstanceRepository, key: Instance.Id): Unit = {
    assert(!state.ids().runWith(Sink.set).futureValue.contains(key), s"Key $key was found in state")
  }

  def stateShouldContainKey(state: InstanceRepository, key: Instance.Id): Unit = {
    assert(state.ids().runWith(Sink.set).futureValue.contains(key), s"Key $key was not found in state")
  }
}
