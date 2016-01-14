package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import com.google.common.collect.Lists
import mesosphere.FutureTestSupport._
import mesosphere.marathon.MarathonSpec
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
  val config = defaultConfig()
  val taskIdUtil = new TaskIdUtil
  val metrics = new Metrics(new MetricRegistry)

  before {
    state = spy(new InMemoryStore)
    val taskTrackerModule = createTaskTrackerModule(AlwaysElectedLeadershipModule(shutdownHooks), state, config, metrics)
    taskTracker = taskTrackerModule.taskTracker
    taskCreationHandler = taskTrackerModule.taskCreationHandler
    taskUpdater = taskTrackerModule.taskUpdater
  }

  test("SerializeAndDeserialize") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue

    val deserializedTask = taskTracker.getTask(TEST_APP_NAME, sampleTask.getId)

    assert(deserializedTask.nonEmpty, "fetch task returned a None")
    assert(deserializedTask.get.equals(sampleTask), "Tasks are not properly serialized")
  }

  test("CreatedAndGetTask") {
    testCreatedAndGetTask(_.getTask(_, _))
  }

  test("CreatedAndGetTask Async") {
    testCreatedAndGetTask(_.getTaskAsync(_, _).futureValue)
  }

  private[this] def testCreatedAndGetTask(call: (TaskTracker, PathId, String) => Option[MarathonTask]): Unit = {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue

    val fetchedTask = call(taskTracker, TEST_APP_NAME, sampleTask.getId)

    assert(fetchedTask.get.equals(sampleTask), "Tasks are not properly stored")
  }

  test("List") {
    testList(_.list)
  }

  test("List Async") {
    testList(_.listAsync().futureValue)
  }

  private[this] def testList(call: TaskTracker => TaskTracker.AppDataMap): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")
    val task2 = makeSampleTask(TEST_APP_NAME / "b")
    val task3 = makeSampleTask(TEST_APP_NAME / "b")

    taskCreationHandler.created(TEST_APP_NAME / "a", task1).futureValue
    taskCreationHandler.created(TEST_APP_NAME / "b", task2).futureValue
    taskCreationHandler.created(TEST_APP_NAME / "b", task3).futureValue

    val testAppTasks = call(taskTracker)

    testAppTasks.keySet should be(Set(TEST_APP_NAME / "a", TEST_APP_NAME / "b"))

    testAppTasks.appTasks(TEST_APP_NAME / "a").appId should equal(TEST_APP_NAME / "a")
    testAppTasks.appTasks(TEST_APP_NAME / "b").appId should equal(TEST_APP_NAME / "b")
    testAppTasks.appTasks(TEST_APP_NAME / "a").tasks should have size 1
    testAppTasks.appTasks(TEST_APP_NAME / "b").tasks should have size 2
    testAppTasks.appTasks(TEST_APP_NAME / "a").tasks.map(_.getId).toSet should equal(Set(task1.getId))
    testAppTasks.appTasks(TEST_APP_NAME / "b").tasks.map(_.getId).toSet should equal(Set(task2.getId, task3.getId))
  }

  test("GetTasks") {
    testGetTasks(_.getTasks(TEST_APP_NAME))
  }

  test("GetTasks Async") {
    testGetTasks(_.getTasksAsync(TEST_APP_NAME).futureValue)
  }

  private[this] def testGetTasks(call: TaskTracker => Iterable[MarathonTask]): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME)
    val task2 = makeSampleTask(TEST_APP_NAME)
    val task3 = makeSampleTask(TEST_APP_NAME)

    taskCreationHandler.created(TEST_APP_NAME, task1).futureValue
    taskCreationHandler.created(TEST_APP_NAME, task2).futureValue
    taskCreationHandler.created(TEST_APP_NAME, task3).futureValue

    val testAppTasks = call(taskTracker)

    shouldContainTask(testAppTasks.toSet, task1)
    shouldContainTask(testAppTasks.toSet, task2)
    shouldContainTask(testAppTasks.toSet, task3)
    assert(testAppTasks.size == 3)
  }

  test("Count") {
    testCount(_.count(_))
  }

  test("Count Async") {
    testCount(_.countAsync(_).futureValue)
  }

  private[this] def testCount(count: (TaskTracker, PathId) => Int): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    taskCreationHandler.created(TEST_APP_NAME / "a", task1).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(1)
    count(taskTracker, TEST_APP_NAME / "b") should be(0)
  }

  test("Contains") {
    testContains(_.contains(_))
  }

  test("Contains Async") {
    testContains(_.containsAsync(_).futureValue)
  }

  private[this] def testContains(count: (TaskTracker, PathId) => Boolean): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    taskCreationHandler.created(TEST_APP_NAME / "a", task1).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(true)
    count(taskTracker, TEST_APP_NAME / "b") should be(false)
  }

  test("TaskLifecycle") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // CREATE TASK
    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue

    shouldContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)

    // TASK STATUS UPDATE
    val startingTaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_STARTING)

    taskUpdater.statusUpdate(TEST_APP_NAME, startingTaskStatus).futureValue

    shouldContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)
    taskTracker.getTasks(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, startingTaskStatus))

    // TASK RUNNING
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_RUNNING)

    taskUpdater.statusUpdate(TEST_APP_NAME, runningTaskStatus).futureValue

    shouldContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)
    taskTracker.getTasks(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK STILL RUNNING
    val updatedRunningTaskStatus = runningTaskStatus.toBuilder.setTimestamp(123).build()
    taskUpdater.statusUpdate(TEST_APP_NAME, updatedRunningTaskStatus).futureValue
    shouldContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    assert(taskTracker.getTasks(TEST_APP_NAME).head.getStatus == runningTaskStatus)

    // TASK TERMINATED
    taskCreationHandler.terminated(TEST_APP_NAME, sampleTask.getId).futureValue

    stateShouldNotContainKey(state, sampleTask.getId)

    // APP SHUTDOWN
    assert(!taskTracker.contains(TEST_APP_NAME), "App was not removed")

    // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
    val erroneousStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_LOST)

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
    val terminalStatusUpdate = makeTaskStatus(sampleTask.getId, taskState)

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
    shouldContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)

    taskUpdater.statusUpdate(TEST_APP_NAME, terminalStatusUpdate).futureValue

    shouldNotContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.getId)
  }

  test("UnknownTasks") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // don't call taskTracker.created, but directly running
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_RUNNING)
    val res = taskUpdater.statusUpdate(TEST_APP_NAME, runningTaskStatus)
    ScalaFutures.whenReady(res.failed) { e =>
      assert(
        e.getCause.getMessage == s"task [${sampleTask.getId}] of app [/foo] does not exist",
        s"Got message: ${e.getCause.getMessage}"
      )
    }
    shouldNotContainTask(taskTracker.getTasks(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.getId)
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

    taskCreationHandler.created(appName1, app1_task1).futureValue
    taskUpdater.statusUpdate(appName1, makeTaskStatus(app1_task1.getId)).futureValue

    taskCreationHandler.created(appName1, app1_task2).futureValue
    taskUpdater.statusUpdate(appName1, makeTaskStatus(app1_task2.getId)).futureValue

    taskCreationHandler.created(appName2, app2_task1).futureValue
    taskUpdater.statusUpdate(appName2, makeTaskStatus(app2_task1.getId)).futureValue

    taskCreationHandler.created(appName3, app3_task1).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task1.getId)).futureValue

    taskCreationHandler.created(appName3, app3_task2).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task2.getId)).futureValue

    taskCreationHandler.created(appName3, app3_task3).futureValue
    taskUpdater.statusUpdate(appName3, makeTaskStatus(app3_task3.getId)).futureValue

    assert(state.allIds().futureValue.size == 6, "Incorrect number of tasks in state")

    val app1Tasks = taskTracker.getTasks(appName1).toSet

    shouldContainTask(app1Tasks, app1_task1)
    shouldContainTask(app1Tasks, app1_task2)
    assert(app1Tasks.size == 2, "Incorrect number of tasks")

    val app2Tasks = taskTracker.getTasks(appName2).toSet

    shouldContainTask(app2Tasks, app2_task1)
    assert(app2Tasks.size == 1, "Incorrect number of tasks")

    val app3Tasks = taskTracker.getTasks(appName3).toSet

    shouldContainTask(app3Tasks, app3_task1)
    shouldContainTask(app3Tasks, app3_task2)
    shouldContainTask(app3Tasks, app3_task3)
    assert(app3Tasks.size == 3, "Incorrect number of tasks")
  }

  test("Should not store if state did not change (no health present)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should not store if state and health did not change") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskUpdater.statusUpdate(TEST_APP_NAME, status).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should store if state changed") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
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
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
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
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
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
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
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
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskCreationHandler.created(TEST_APP_NAME, sampleTask).futureValue
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
    def makeTask(id: String, host: String, port: Int) = {
      MarathonTask.newBuilder()
        .setHost(host)
        .addAllPorts(Lists.newArrayList(port))
        .setId(id)
        .addAttributes(TextAttribute("attr1", "bar"))
        .build()
    }

    val taskId = TaskIdUtil.newTaskId(appId)
    makeTask(taskId.getValue, "host", 999)
  }

  def makeTaskStatus(id: String, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder
        .setValue(id)
      )
      .setState(state)
      .build
  }

  def containsTask(tasks: Iterable[MarathonTask], task: MarathonTask) =
    tasks.exists(t => t.getId == task.getId
      && t.getHost == task.getHost
      && t.getPortsList == task.getPortsList)
  def shouldContainTask(tasks: Iterable[MarathonTask], task: MarathonTask) =
    assert(containsTask(tasks, task), s"Should contain task ${task.getId}")
  def shouldNotContainTask(tasks: Iterable[MarathonTask], task: MarathonTask) =
    assert(!containsTask(tasks, task), s"Should not contain task ${task.getId}")

  def shouldHaveTaskStatus(task: MarathonTask, taskStatus: TaskStatus) {
    assert(
      task.getStatus == taskStatus, s"Should have task status ${taskStatus.getState.toString}"
    )
  }

  def stateShouldNotContainKey(state: PersistentStore, key: String) {
    val keyWithPrefix = TaskRepository.storePrefix + key
    assert(!state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was found in state")
  }

  def stateShouldContainKey(state: PersistentStore, key: String) {
    val keyWithPrefix = TaskRepository.storePrefix + key
    assert(state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was not found in state")
  }
}