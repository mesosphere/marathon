package mesosphere.marathon.tasks
// TODO AW: test
/*
import com.codahale.metrics.MetricRegistry
import com.google.common.collect.Lists
import mesosphere.FutureTestSupport._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ PathId, TaskRepository, Timestamp }
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
import scala.concurrent.duration._

class TaskTrackerTest extends MarathonSpec with Matchers with GivenWhenThen {

  val TEST_APP_NAME = "foo".toRootPath
  var taskTracker: TaskTracker = null
  var state: PersistentStore = null
  val config = defaultConfig()
  val taskIdUtil = new TaskIdUtil
  val metrics = new Metrics(new MetricRegistry)

  before {
    state = spy(new InMemoryStore)
    taskTracker = createTaskTracker(state, config, metrics)
  }

  test("SerializeAndDeserialize") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue

    val deserializedTask = taskTracker.fetchTask(sampleTask.getId)

    assert(deserializedTask.nonEmpty, "fetch task returned a None")
    assert(deserializedTask.get.equals(sampleTask), "Tasks are not properly serialized")
  }

  test("StoreAndFetchTask") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue

    val fetchedTask = taskTracker.fetchTask(sampleTask.getId)

    assert(fetchedTask.get.equals(sampleTask), "Tasks are not properly stored")
  }

  test("FetchApp") {
    val task1 = makeSampleTask(TEST_APP_NAME)
    val task2 = makeSampleTask(TEST_APP_NAME)
    val task3 = makeSampleTask(TEST_APP_NAME)

    taskTracker.store(TEST_APP_NAME, task1).futureValue
    taskTracker.store(TEST_APP_NAME, task2).futureValue
    taskTracker.store(TEST_APP_NAME, task3).futureValue

    val testAppTasks = taskTracker.fetchApp(TEST_APP_NAME).tasks

    shouldContainTask(testAppTasks.values.toSet, task1)
    shouldContainTask(testAppTasks.values.toSet, task2)
    shouldContainTask(testAppTasks.values.toSet, task3)
    assert(testAppTasks.size == 3)
  }

  test("Clear state of TaskTracker") {
    val task1 = makeSampleTask(TEST_APP_NAME)
    taskTracker.store(TEST_APP_NAME, task1).futureValue
    val testAppTasks = taskTracker.get(TEST_APP_NAME)
    taskTracker.apps should have size 1
    taskTracker.clear()
    taskTracker.apps should have size 0
  }

  test("TaskLifecycle") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // CREATE TASK
    taskTracker.created(TEST_APP_NAME, sampleTask)

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.getId)

    // TASK STATUS UPDATE
    val startingTaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_STARTING)

    taskTracker.statusUpdate(TEST_APP_NAME, startingTaskStatus).futureValue

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)
    taskTracker.get(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, startingTaskStatus))

    // TASK RUNNING
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_RUNNING)

    taskTracker.running(TEST_APP_NAME, runningTaskStatus).futureValue

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.getId)
    taskTracker.get(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK STILL RUNNING
    val res = taskTracker.running(TEST_APP_NAME, runningTaskStatus)
    ScalaFutures.whenReady(res.failed) { e =>
      assert(e.getMessage == s"Task for ID ${sampleTask.getId} already running, ignoring")
    }

    // TASK TERMINATED
    taskTracker.terminated(TEST_APP_NAME, sampleTask.getId).futureValue

    assert(taskTracker.contains(TEST_APP_NAME), "App was not stored")
    stateShouldNotContainKey(state, sampleTask.getId)

    // APP SHUTDOWN
    taskTracker.shutdown(TEST_APP_NAME)

    assert(!taskTracker.contains(TEST_APP_NAME), "App was not removed")

    // ERRONEOUS MESSAGE
    val erroneousStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_LOST)

    val taskOption = taskTracker.statusUpdate(TEST_APP_NAME, erroneousStatus).futureValue

    // Empty option means this message was discarded since there was no matching task
    assert(taskOption.isEmpty, "Task was able to be updated and was not removed")
  }

  test("UnknownTasks") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // don't call taskTracker.created, but directly running
    val runningTaskStatus: TaskStatus = makeTaskStatus(sampleTask.getId, TaskState.TASK_RUNNING)
    val res = taskTracker.running(TEST_APP_NAME, runningTaskStatus)
    ScalaFutures.whenReady(res.failed) { e =>
      assert(e.getMessage == s"No staged task for ID ${sampleTask.getId}, ignoring")
    }
    shouldNotContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
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

    taskTracker.created(appName1, app1_task1)
    taskTracker.running(appName1, makeTaskStatus(app1_task1.getId)).futureValue

    taskTracker.created(appName1, app1_task2)
    taskTracker.running(appName1, makeTaskStatus(app1_task2.getId)).futureValue

    taskTracker.created(appName2, app2_task1)
    taskTracker.running(appName2, makeTaskStatus(app2_task1.getId)).futureValue

    taskTracker.created(appName3, app3_task1)
    taskTracker.running(appName3, makeTaskStatus(app3_task1.getId)).futureValue

    taskTracker.created(appName3, app3_task2)
    taskTracker.running(appName3, makeTaskStatus(app3_task2.getId)).futureValue

    taskTracker.created(appName3, app3_task3)
    taskTracker.running(appName3, makeTaskStatus(app3_task3.getId)).futureValue

    assert(state.allIds().futureValue.size == 6, "Incorrect number of tasks in state")

    val app1Tasks = taskTracker.fetchApp(appName1).tasks

    shouldContainTask(app1Tasks.values.toSet, app1_task1)
    shouldContainTask(app1Tasks.values.toSet, app1_task2)
    assert(app1Tasks.size == 2, "Incorrect number of tasks")

    val app2Tasks = taskTracker.fetchApp(appName2).tasks

    shouldContainTask(app2Tasks.values.toSet, app2_task1)
    assert(app2Tasks.size == 1, "Incorrect number of tasks")

    val app3Tasks = taskTracker.fetchApp(appName3).tasks

    shouldContainTask(app3Tasks.values.toSet, app3_task1)
    shouldContainTask(app3Tasks.values.toSet, app3_task2)
    shouldContainTask(app3Tasks.values.toSet, app3_task3)
    assert(app3Tasks.size == 3, "Incorrect number of tasks")
  }

  test("ExpungeOrphanedTasks") {
    val ORPHANED_APP_NAME = "orphanedApp".toRootPath

    val orphanedTask1 = makeSampleTask(ORPHANED_APP_NAME)
    val orphanedTask2 = makeSampleTask(ORPHANED_APP_NAME)
    val orphanedTask3 = makeSampleTask(ORPHANED_APP_NAME)

    taskTracker.store(ORPHANED_APP_NAME, orphanedTask1).futureValue
    taskTracker.store(ORPHANED_APP_NAME, orphanedTask2).futureValue
    taskTracker.store(ORPHANED_APP_NAME, orphanedTask3).futureValue

    val task1 = makeSampleTask(TEST_APP_NAME)
    val task2 = makeSampleTask(TEST_APP_NAME)
    val task3 = makeSampleTask(TEST_APP_NAME)

    taskTracker.created(TEST_APP_NAME, task1)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(task1.getId)).futureValue

    taskTracker.created(TEST_APP_NAME, task2)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(task2.getId)).futureValue

    taskTracker.created(TEST_APP_NAME, task3)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(task3.getId)).futureValue

    taskTracker.expungeOrphanedTasks()

    val names = state.allIds().futureValue

    assert(names.size == 3, "Orphaned tasks were not correctly expunged")
    assert(!taskTracker.contains(ORPHANED_APP_NAME), "Orphaned app should not exist in TaskTracker")

    val tasks = taskTracker.get(TEST_APP_NAME)

    shouldContainTask(tasks, task1)
    shouldContainTask(tasks, task2)
    shouldContainTask(tasks, task3)
  }

  test("Should not store if state did not change (no health present)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

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

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should store if state changed") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if health changed") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus).futureValue

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

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(true)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if state and health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask).futureValue
    taskTracker.running(TEST_APP_NAME, status).futureValue

    taskTracker.statusUpdate(TEST_APP_NAME, status).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus).futureValue

    verify(state, times(1)).update(any())
  }

  // sounds strange, but this is how it currently works: determineOverdueTasks will consider a missing startedAt to
  // determine whether a task is in staging and might need to be killed if it exceeded the taskLaunchTimeout
  test("ensure that determineOverdueTasks returns tasks disregarding the stagedAt property") {
    import scala.language.implicitConversions
    implicit def toMillis(timestamp: Timestamp): Long = timestamp.toDateTime.getMillis

    val clock = ConstantClock(Timestamp.now())
    val now = clock.now()

    val overdueUnstagedTask = MarathonTask.newBuilder()
      .setId("unstaged")
      .build()
    assert(overdueUnstagedTask.getStagedAt == 0, "The stagedAt property of an unstaged task has a value of 0")
    assert(overdueUnstagedTask.getStartedAt == 0, "The startedAt property of an unstaged task has a value of 0")

    val unconfirmedNotOverdueTask = MarathonTask.newBuilder()
      .setId("unconfirmed")
      .setStagedAt(now - config.taskLaunchConfirmTimeout().millis)
      .build()

    val unconfirmedOverdueTask = MarathonTask.newBuilder()
      .setId("unconfirmedOverdue")
      .setStagedAt(now - config.taskLaunchConfirmTimeout().millis - 1.millis)
      .build()

    val overdueStagedTask = MarathonTask.newBuilder()
      .setId("overdueStagedTask")
      // When using MarathonTasks.makeTask, this would be set to a made up value
      // This test shall explicitly make sure that the task gets selected even if it is unlikely old
      .setStagedAt(now - 10.days)
      .setStatus(TaskStatus.newBuilder().setState(TaskState.TASK_STAGING).buildPartial())
      .buildPartial()

    val stagedTask = MarathonTask.newBuilder()
      .setId("staged")
      .setStatus(TaskStatus.newBuilder().setState(TaskState.TASK_STAGING).buildPartial())
      .setStagedAt(now - 10.seconds)
      .buildPartial()

    val runningTask = MarathonTask.newBuilder()
      .setId("running")
      .setStatus(TaskStatus.newBuilder().setState(TaskState.TASK_RUNNING).buildPartial())
      .setStagedAt(now - 5.seconds)
      .setStartedAt(now - 2.seconds)
      .buildPartial()

    Given("Several somehow overdue tasks plus some not overdue tasks")
    taskTracker.created(TEST_APP_NAME, unconfirmedOverdueTask)
    taskTracker.created(TEST_APP_NAME, unconfirmedNotOverdueTask)
    taskTracker.created(TEST_APP_NAME, overdueUnstagedTask)
    taskTracker.created(TEST_APP_NAME, overdueStagedTask)
    taskTracker.created(TEST_APP_NAME, stagedTask)
    taskTracker.created(TEST_APP_NAME, runningTask)

    When("We check which tasks should be killed because they're not yet staged or unconfirmed")
    val overdueTasks = taskTracker.determineOverdueTasks(now)

    Then("All somehow overdue tasks are returned")
    assert(overdueTasks.map(_.getId).toSet ==
      Set(overdueStagedTask, overdueUnstagedTask, unconfirmedOverdueTask).map(_.getId))

    assert(overdueTasks.toSet ==
      Set(overdueStagedTask, overdueUnstagedTask, unconfirmedOverdueTask))
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
*/
