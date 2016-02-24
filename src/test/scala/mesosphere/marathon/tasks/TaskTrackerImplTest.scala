package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import com.google.common.collect.Lists
import mesosphere.FutureTestSupport._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.task.tracker.{ TaskTracker, TaskCreationHandler, TaskUpdater }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ PathId, TaskRepository }
import mesosphere.marathon.test.MarathonShutdownHookSupport
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import mesosphere.util.state.PersistentStore
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.mockito.Matchers.any
import org.mockito.Mockito.{ reset, spy, times, verify }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection._

class TaskTrackerImplTest extends MarathonSpec with Matchers with GivenWhenThen with MarathonShutdownHookSupport {

  import scala.concurrent.ExecutionContext.Implicits.global

  val TEST_APP_NAME = "foo".toRootPath
  var taskTracker: TaskTracker = null
  var taskCreationHandler: TaskCreationHandler = null
  var taskUpdater: TaskUpdater = null
  var state: PersistentStore = null
  val config = MarathonTestHelper.defaultConfig()
  val metrics = new Metrics(new MetricRegistry)

  before {
    state = spy(new InMemoryStore)
    val taskTrackerModule = MarathonTestHelper.createTaskTrackerModule(AlwaysElectedLeadershipModule(shutdownHooks), state, config, metrics)
    taskTracker = taskTrackerModule.taskTracker
    taskCreationHandler = taskTrackerModule.taskCreationHandler
    taskUpdater = taskTrackerModule.taskUpdater
  }

  test("SerializeAndDeserialize") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(sampleTask).futureValue

    val deserializedTask = taskTracker.marathonTaskSync(sampleTask.taskId)

    deserializedTask should not be empty
    deserializedTask should equal(Some(sampleTask.marathonTask))
  }

  test("CreatedAndGetTask") {
    testCreatedAndGetTask(_.marathonTaskSync(_))
  }

  test("CreatedAndGetTask Async") {
    testCreatedAndGetTask(_.marathonTask(_).futureValue)
  }

  private[this] def testCreatedAndGetTask(call: (TaskTracker, Task.Id) => Option[MarathonTask]): Unit = {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(sampleTask).futureValue

    val fetchedTask = call(taskTracker, sampleTask.taskId)

    assert(fetchedTask.get.equals(sampleTask.marathonTask), "Tasks are not properly stored")
  }

  test("List") {
    testList(_.tasksByAppSync)
  }

  test("List Async") {
    testList(_.tasksByApp().futureValue)
  }

  private[this] def testList(call: TaskTracker => TaskTracker.TasksByApp): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")
    val task2 = makeSampleTask(TEST_APP_NAME / "b")
    val task3 = makeSampleTask(TEST_APP_NAME / "b")

    taskCreationHandler.created(task1).futureValue
    taskCreationHandler.created(task2).futureValue
    taskCreationHandler.created(task3).futureValue

    val testAppTasks = call(taskTracker)

    testAppTasks.allAppIdsWithTasks should be(Set(TEST_APP_NAME / "a", TEST_APP_NAME / "b"))

    testAppTasks.appTasksMap(TEST_APP_NAME / "a").appId should equal(TEST_APP_NAME / "a")
    testAppTasks.appTasksMap(TEST_APP_NAME / "b").appId should equal(TEST_APP_NAME / "b")
    testAppTasks.appTasksMap(TEST_APP_NAME / "a").marathonTasks should have size 1
    testAppTasks.appTasksMap(TEST_APP_NAME / "b").marathonTasks should have size 2
    testAppTasks.appTasksMap(TEST_APP_NAME / "a").taskMap.keySet should equal(Set(task1.taskId))
    testAppTasks.appTasksMap(TEST_APP_NAME / "b").taskMap.keySet should equal(Set(task2.taskId, task3.taskId))
  }

  test("GetTasks") {
    testGetTasks(_.appTasksSync(TEST_APP_NAME))
  }

  test("GetTasks Async") {
    testGetTasks(_.appTasks(TEST_APP_NAME).futureValue)
  }

  private[this] def testGetTasks(call: TaskTracker => Iterable[Task]): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME)
    val task2 = makeSampleTask(TEST_APP_NAME)
    val task3 = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(task1).futureValue
    taskCreationHandler.created(task2).futureValue
    taskCreationHandler.created(task3).futureValue

    val testAppTasks = call(taskTracker).map(_.marathonTask).map(TaskSerializer.fromProto(_))

    shouldContainTask(testAppTasks.toSet, task1)
    shouldContainTask(testAppTasks.toSet, task2)
    shouldContainTask(testAppTasks.toSet, task3)
    assert(testAppTasks.size == 3)
  }

  test("Count") {
    testCount(_.countLaunchedAppTasksSync(_))
  }

  test("Count Async") {
    testCount(_.countAppTasks(_).futureValue)
  }

  private[this] def testCount(count: (TaskTracker, PathId) => Int): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    taskCreationHandler.created(task1).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(1)
    count(taskTracker, TEST_APP_NAME / "b") should be(0)
  }

  test("Contains") {
    testContains(_.hasAppTasksSync(_))
  }

  test("Contains Async") {
    testContains(_.hasAppTasks(_).futureValue)
  }

  private[this] def testContains(count: (TaskTracker, PathId) => Boolean): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    taskCreationHandler.created(task1).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(true)
    count(taskTracker, TEST_APP_NAME / "b") should be(false)
  }

  test("TaskLifecycle") {
    val sampleTask = MarathonTestHelper.startingTaskForApp(TEST_APP_NAME)

    // CREATE TASK
    taskCreationHandler.created(sampleTask).futureValue

    shouldContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)

    // TASK STATUS UPDATE
    val startingTaskStatus = makeTaskStatus(sampleTask.taskId, TaskState.TASK_STARTING)

    taskUpdater.statusUpdate(TEST_APP_NAME, startingTaskStatus).futureValue

    shouldContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)
    taskTracker.appTasksSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, startingTaskStatus))

    // TASK RUNNING
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.taskId, TaskState.TASK_RUNNING)

    taskUpdater.statusUpdate(TEST_APP_NAME, runningTaskStatus).futureValue

    shouldContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)
    taskTracker.appTasksSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK STILL RUNNING
    val updatedRunningTaskStatus = runningTaskStatus.toBuilder.setTimestamp(123).build()
    taskUpdater.statusUpdate(TEST_APP_NAME, updatedRunningTaskStatus).futureValue
    shouldContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    assert(taskTracker.appTasksSync(TEST_APP_NAME).head.launched.get.status.mesosStatus.get == runningTaskStatus)

    // TASK TERMINATED
    taskCreationHandler.terminated(sampleTask.taskId).futureValue

    stateShouldNotContainKey(state, sampleTask.taskId)

    // APP SHUTDOWN
    assert(!taskTracker.hasAppTasksSync(TEST_APP_NAME), "App was not removed")

    // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
    val erroneousStatus = makeTaskStatus(sampleTask.taskId, TaskState.TASK_LOST)

    val failure = taskUpdater.statusUpdate(TEST_APP_NAME, erroneousStatus).failed.futureValue
    assert(failure.getCause != null)
    assert(failure.getCause.getMessage.contains("does not exist"), s"message: ${failure.getMessage}")
  }

  test("TASK_FAILED status update will expunge task") { testStatusUpdateForTerminalState(TaskState.TASK_FAILED) }
  test("TASK_FINISHED status update will expunge task") { testStatusUpdateForTerminalState(TaskState.TASK_FINISHED) }
  test("TASK_LOST status update will expunge task") { testStatusUpdateForTerminalState(TaskState.TASK_LOST) }
  test("TASK_KILLED status update will expunge task") { testStatusUpdateForTerminalState(TaskState.TASK_KILLED) }
  test("TASK_ERROR status update will expunge task") { testStatusUpdateForTerminalState(TaskState.TASK_ERROR) }

  private[this] def testStatusUpdateForTerminalState(taskState: TaskState) {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val terminalStatusUpdate = makeTaskStatus(sampleTask.taskId, taskState)

    taskCreationHandler.created(sampleTask).futureValue
    shouldContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)

    taskUpdater.statusUpdate(TEST_APP_NAME, terminalStatusUpdate).futureValue

    shouldNotContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.taskId)
  }

  test("UnknownTasks") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // don't call taskTracker.created, but directly running
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.taskId, TaskState.TASK_RUNNING)
    val res = taskUpdater.statusUpdate(TEST_APP_NAME, runningTaskStatus)
    ScalaFutures.whenReady(res.failed) { e =>
      assert(
        e.getCause.getMessage == s"${sampleTask.taskId} of app [/foo] does not exist",
        s"Got message: ${e.getCause.getMessage}"
      )
    }
    shouldNotContainTask(taskTracker.appTasksSync(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.taskId)
  }

  test("MultipleApps") {
    val appName1 = "app1".toRootPath
    val appName2 = "app2".toRootPath
    val appName3 = "app3".toRootPath

    val app1_task1 = makeSampleTask(appName1)
    val app1_task2 = makeSampleTask(appName1)
    val app2_task1 = makeSampleTask(appName2)
    val app3_task1 = makeSampleTask(appName3)
    val app3_task2 = makeSampleTask(appName3)
    val app3_task3 = makeSampleTask(appName3)

    taskCreationHandler.created(app1_task1).futureValue
    taskUpdater.statusUpdate(appName1, makeTaskStatus(app1_task1.taskId)).futureValue

    taskCreationHandler.created(app1_task2).futureValue
    taskUpdater.statusUpdate(appName1, makeTaskStatus(app1_task2.taskId)).futureValue

    taskCreationHandler.created(app2_task1).futureValue
    taskUpdater.statusUpdate(appName2, makeTaskStatus(app2_task1.taskId)).futureValue

    taskCreationHandler.created(app3_task1).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task1.taskId)).futureValue

    taskCreationHandler.created(app3_task2).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task2.taskId)).futureValue

    taskCreationHandler.created(app3_task3).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task3.taskId)).futureValue

    assert(state.allIds().futureValue.size == 6, "Incorrect number of tasks in state")

    val app1Tasks = taskTracker.appTasksSync(appName1).toSet

    shouldContainTask(app1Tasks, app1_task1)
    shouldContainTask(app1Tasks, app1_task2)
    assert(app1Tasks.size == 2, "Incorrect number of tasks")

    val app2Tasks = taskTracker.appTasksSync(appName2).toSet

    shouldContainTask(app2Tasks, app2_task1)
    assert(app2Tasks.size == 1, "Incorrect number of tasks")

    val app3Tasks = taskTracker.appTasksSync(appName3).toSet

    shouldContainTask(app3Tasks, app3_task1)
    shouldContainTask(app3Tasks, app3_task2)
    shouldContainTask(app3Tasks, app3_task3)
    assert(app3Tasks.size == 3, "Incorrect number of tasks")
  }

  test("Should not store if state did not change (no health present)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get
      .toBuilder
      .setTimestamp(123)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should not store if state and health did not change") {
    val sampleTask = MarathonTestHelper.healthyTask(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get
      .toBuilder
      .setTimestamp(123)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should store if state changed") {
    val sampleTask = MarathonTestHelper.stagedTaskForApp(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .build()

    taskUpdater.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).delete(any())
  }

  test("Should store if health changed") {
    val sampleTask = MarathonTestHelper.runningTaskForApp(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get.toBuilder
      .setHealthy(true)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(false)
      .build()

    taskUpdater.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if state and health changed") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(sampleTask.taskId.mesosTaskId)
      .setHealthy(true)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build()

    taskUpdater.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(sampleTask.taskId.mesosTaskId)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(true)
      .build()

    taskUpdater.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if state and health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(sampleTask.taskId.mesosTaskId)
      .build()

    taskCreationHandler.created(sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build()

    taskUpdater.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  def makeSampleTask(appId: PathId) = {
    MarathonTestHelper
      .stagedTaskForApp(appId)
      .withAgentInfo(_.copy(host = "host", attributes = Iterable(TextAttribute("attr1", "bar"))))
      .withLaunched(_.copy(networking = Task.HostPorts(Iterable(999))))
  }

  def makeTaskStatus(id: Task.Id, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(id.mesosTaskId)
      .setState(state)
      .build
  }

  def containsTask(tasks: Iterable[Task], task: Task) =
    tasks.exists(t => t.taskId == task.taskId
      && t.agentInfo.host == task.agentInfo.host
      && t.launched.map(_.networking) == task.launched.map(_.networking))
  def shouldContainTask(tasks: Iterable[Task], task: Task) =
    assert(containsTask(tasks, task), s"Should contain ${task.taskId}")
  def shouldNotContainTask(tasks: Iterable[Task], task: Task) =
    assert(!containsTask(tasks, task), s"Should not contain ${task.taskId}")

  def shouldHaveTaskStatus(task: Task, taskStatus: TaskStatus) {
    assert(
      task.launched.exists(_.status.mesosStatus.get == taskStatus),
      s"Should have task status ${taskStatus.getState.toString}"
    )
  }

  def stateShouldNotContainKey(state: PersistentStore, key: Task.Id) {
    val keyWithPrefix = TaskRepository.storePrefix + key.idString
    assert(!state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was found in state")
  }

  def stateShouldContainKey(state: PersistentStore, key: Task.Id) {
    val keyWithPrefix = TaskRepository.storePrefix + key.idString
    assert(state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was not found in state")
  }
}