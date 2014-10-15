package mesosphere.marathon.tasks

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

import com.codahale.metrics.MetricRegistry
import com.google.common.collect.Lists
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.apache.mesos.state.{ InMemoryState, State }
import org.mockito.Mockito.{ reset, spy, times, verify }
import org.mockito.Matchers.any

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class TaskTrackerTest extends MarathonSpec {

  val TEST_APP_NAME = "foo".toRootPath
  val TEST_TASK_ID = "sampleTask"
  var taskTracker: TaskTracker = null
  var state: State = null
  val config = mock[MarathonConf]
  val taskIdUtil = new TaskIdUtil
  val registry = new MetricRegistry

  before {
    state = spy(new InMemoryState)
    taskTracker = new TaskTracker(state, config, registry)
  }

  def makeSampleTask(id: String) = {
    makeTask(id, "host", 999)
  }

  def makeTask(id: String, host: String, port: Int) = {
    MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(port))
      .setId(id)
      .addAttributes(TextAttribute("attr1", "bar"))
      .build()
  }

  def makeTaskStatus(id: String, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder
        .setValue(id)
      )
      .setState(state)
      .build
  }

  def shouldContainTask(tasks: Iterable[MarathonTask], task: MarathonTask) {
    assert(
      tasks.exists(t => t.getId == task.getId
        && t.getHost == task.getHost
        && t.getPortsList == task.getPortsList),
      s"Should contain task ${task.getId}"
    )
  }

  def shouldHaveTaskStatus(task: MarathonTask, taskStatus: TaskStatus) {
    assert(
      task.getStatus == taskStatus, s"Should have task status ${taskStatus.getState.toString}"
    )
  }

  def stateShouldNotContainKey(state: State, key: String) {
    assert(!state.names().get().asScala.toSet.contains(key), s"Key ${key} was found in state")
  }

  def stateShouldContainKey(state: State, key: String) {
    assert(state.names().get().asScala.toSet.contains(key), s"Key ${key} was not found in state")
  }

  test("SerializeAndDeserialize") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val byteOutputStream = new ByteArrayOutputStream()
    val outputStream = new ObjectOutputStream(byteOutputStream)

    taskTracker.serialize(sampleTask, outputStream)

    val byteInputStream = new ByteArrayInputStream(byteOutputStream.toByteArray)
    val inputStream = new ObjectInputStream(byteInputStream)

    val deserializedTask = taskTracker.deserialize(taskTracker.getKey(TEST_APP_NAME, TEST_TASK_ID), inputStream)

    assert(deserializedTask.get.equals(sampleTask), "Tasks are not properly serialized")
  }

  test("StoreAndFetchTask") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)

    taskTracker.store(TEST_APP_NAME, sampleTask)

    val fetchedTask = taskTracker.fetchTask(taskTracker.getKey(TEST_APP_NAME, TEST_TASK_ID))

    assert(fetchedTask.get.equals(sampleTask), "Tasks are not properly stored")
  }

  test("FetchApp") {
    val taskId1 = taskIdUtil.taskId(TEST_APP_NAME)
    val taskId2 = taskIdUtil.taskId(TEST_APP_NAME)
    val taskId3 = taskIdUtil.taskId(TEST_APP_NAME)

    val task1 = makeSampleTask(taskId1)
    val task2 = makeSampleTask(taskId2)
    val task3 = makeSampleTask(taskId3)

    taskTracker.store(TEST_APP_NAME, task1)
    taskTracker.store(TEST_APP_NAME, task2)
    taskTracker.store(TEST_APP_NAME, task3)

    val testAppTasks = taskTracker.fetchApp(TEST_APP_NAME).tasks

    shouldContainTask(testAppTasks.values.toSet, task1)
    shouldContainTask(testAppTasks.values.toSet, task2)
    shouldContainTask(testAppTasks.values.toSet, task3)
    assert(testAppTasks.size == 3)
  }

  test("TaskLifecycle") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val sampleTaskKey = taskTracker.getKey(TEST_APP_NAME, TEST_TASK_ID)

    // CREATE TASK
    taskTracker.created(TEST_APP_NAME, sampleTask)

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTaskKey)

    // TASK STATUS UPDATE
    val startingTaskStatus = makeTaskStatus(TEST_TASK_ID, TaskState.TASK_STARTING)

    taskTracker.statusUpdate(TEST_APP_NAME, startingTaskStatus)

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTaskKey)
    taskTracker.get(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, startingTaskStatus))

    // TASK RUNNING
    val runningTaskStatus: TaskStatus = makeTaskStatus(TEST_TASK_ID, TaskState.TASK_RUNNING)

    taskTracker.running(TEST_APP_NAME, runningTaskStatus)

    shouldContainTask(taskTracker.get(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTaskKey)
    taskTracker.get(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK TERMINATED
    val finishedTaskStatus = makeTaskStatus(TEST_TASK_ID, TaskState.TASK_FINISHED)

    taskTracker.terminated(TEST_APP_NAME, finishedTaskStatus)

    assert(taskTracker.contains(TEST_APP_NAME), "App was not stored")
    stateShouldNotContainKey(state, sampleTaskKey)

    // APP SHUTDOWN
    taskTracker.shutdown(TEST_APP_NAME)

    assert(!taskTracker.contains(TEST_APP_NAME), "App was not removed")

    // ERRONEOUS MESSAGE
    val erroneousStatus = makeTaskStatus(TEST_TASK_ID, TaskState.TASK_LOST)

    val updatedTask = taskTracker.statusUpdate(TEST_APP_NAME, erroneousStatus)

    val taskOption = Await.result(updatedTask, Duration.Inf)

    // Empty option means this message was discarded since there was no matching task
    assert(taskOption.isEmpty, "Task was able to be updated and was not removed")
  }

  test("MultipleApps") {
    val appName1 = "app1".toRootPath
    val appName2 = "app2".toRootPath
    val appName3 = "app3".toRootPath

    val taskId1 = taskIdUtil.taskId(appName1)
    val taskId2 = taskIdUtil.taskId(appName1)
    val taskId3 = taskIdUtil.taskId(appName2)
    val taskId4 = taskIdUtil.taskId(appName3)
    val taskId5 = taskIdUtil.taskId(appName3)
    val taskId6 = taskIdUtil.taskId(appName3)

    val task1 = makeSampleTask(taskId1)
    val task2 = makeSampleTask(taskId2)
    val task3 = makeSampleTask(taskId3)
    val task4 = makeSampleTask(taskId4)
    val task5 = makeSampleTask(taskId5)
    val task6 = makeSampleTask(taskId6)

    taskTracker.created(appName1, task1)
    taskTracker.running(appName1, makeTaskStatus(taskId1))

    taskTracker.created(appName1, task2)
    taskTracker.running(appName1, makeTaskStatus(taskId2))

    taskTracker.created(appName2, task3)
    taskTracker.running(appName2, makeTaskStatus(taskId3))

    taskTracker.created(appName3, task4)
    taskTracker.running(appName3, makeTaskStatus(taskId4))

    taskTracker.created(appName3, task5)
    taskTracker.running(appName3, makeTaskStatus(taskId5))

    taskTracker.created(appName3, task6)
    taskTracker.running(appName3, makeTaskStatus(taskId6))

    assert(state.names.get.asScala.toSet.size == 6, "Incorrect number of tasks in state")

    val app1Tasks = taskTracker.fetchApp(appName1).tasks

    shouldContainTask(app1Tasks.values.toSet, task1)
    shouldContainTask(app1Tasks.values.toSet, task2)
    assert(app1Tasks.size == 2, "Incorrect number of tasks")

    val app2Tasks = taskTracker.fetchApp(appName2).tasks

    shouldContainTask(app2Tasks.values.toSet, task3)
    assert(app2Tasks.size == 1, "Incorrect number of tasks")

    val app3Tasks = taskTracker.fetchApp(appName3).tasks

    shouldContainTask(app3Tasks.values.toSet, task4)
    shouldContainTask(app3Tasks.values.toSet, task5)
    shouldContainTask(app3Tasks.values.toSet, task6)
    assert(app3Tasks.size == 3, "Incorrect number of tasks")
  }

  test("ExpungeOrphanedTasks") {
    val ORPHANED_APP_NAME = "orphanedApp".toRootPath

    val orphanedTaskId1 = taskIdUtil.taskId(ORPHANED_APP_NAME)
    val orphanedTaskId2 = taskIdUtil.taskId(ORPHANED_APP_NAME)
    val orphanedTaskId3 = taskIdUtil.taskId(ORPHANED_APP_NAME)

    val orphanedTask1 = makeSampleTask(orphanedTaskId1)
    val orphanedTask2 = makeSampleTask(orphanedTaskId2)
    val orphanedTask3 = makeSampleTask(orphanedTaskId3)

    taskTracker.store(ORPHANED_APP_NAME, orphanedTask1)
    taskTracker.store(ORPHANED_APP_NAME, orphanedTask2)
    taskTracker.store(ORPHANED_APP_NAME, orphanedTask3)

    val taskId1 = taskIdUtil.taskId(TEST_APP_NAME)
    val taskId2 = taskIdUtil.taskId(TEST_APP_NAME)
    val taskId3 = taskIdUtil.taskId(TEST_APP_NAME)

    val task1 = makeSampleTask(taskId1)
    val task2 = makeSampleTask(taskId2)
    val task3 = makeSampleTask(taskId3)

    taskTracker.created(TEST_APP_NAME, task1)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(taskId1))

    taskTracker.created(TEST_APP_NAME, task2)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(taskId2))

    taskTracker.created(TEST_APP_NAME, task3)
    taskTracker.running(TEST_APP_NAME, makeTaskStatus(taskId3))

    taskTracker.expungeOrphanedTasks()

    val names = state.names.get.asScala.toSet

    assert(names.size == 3, "Orphaned tasks were not correctly expunged")
    assert(!taskTracker.contains(ORPHANED_APP_NAME), "Orphaned app should not exist in TaskTracker")

    val tasks = taskTracker.get(TEST_APP_NAME)

    shouldContainTask(tasks, task1)
    shouldContainTask(tasks, task2)
    shouldContainTask(tasks, task3)
  }

  test("Should not store if state did not change (no health present)") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    verify(state, times(0)).store(any())
  }

  test("Should not store if state and health did not change") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    verify(state, times(0)).store(any())
  }

  test("Should store if state changed") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus)

    verify(state, times(1)).store(any())
  }

  test("Should store if health changed") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus)

    verify(state, times(1)).store(any())
  }

  test("Should store if state and health changed") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .setHealthy(true)
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus)

    verify(state, times(1)).store(any())
  }

  test("Should store if health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(true)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus)

    verify(state, times(1)).store(any())
  }

  test("Should store if state and health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_TASK_ID)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(Protos.TaskID.newBuilder.setValue(sampleTask.getId))
      .build()

    taskTracker.store(TEST_APP_NAME, sampleTask)
    taskTracker.running(TEST_APP_NAME, status)

    taskTracker.statusUpdate(TEST_APP_NAME, status)

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .setHealthy(false)
      .build()

    taskTracker.statusUpdate(TEST_APP_NAME, newStatus)

    verify(state, times(1)).store(any())
  }
}
