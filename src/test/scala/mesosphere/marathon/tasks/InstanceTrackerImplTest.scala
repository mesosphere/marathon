package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import mesosphere.FutureTestSupport._
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.storage.repository.legacy.TaskEntityRepository
import mesosphere.marathon.storage.repository.legacy.store.{ InMemoryStore, PersistentStore }
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, InstanceTracker }
import mesosphere.marathon.core.task.{ Task, InstanceStateOp }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonShutdownHookSupport }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.apache.mesos.Protos
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.mockito.Matchers.any
import org.mockito.Mockito.{ reset, spy, times, verify }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class InstanceTrackerImplTest extends MarathonSpec with MarathonActorSupport
    with Matchers with GivenWhenThen with MarathonShutdownHookSupport {

  import scala.concurrent.ExecutionContext.Implicits.global

  val TEST_APP_NAME = "foo".toRootPath
  var taskTracker: InstanceTracker = null
  var stateOpProcessor: TaskStateOpProcessor = null
  var state: PersistentStore = null
  val config = MarathonTestHelper.defaultConfig()
  val metrics = new Metrics(new MetricRegistry)
  val clock = ConstantClock()

  before {
    state = spy(new InMemoryStore)
    val taskTrackerModule = MarathonTestHelper.createTaskTrackerModule(AlwaysElectedLeadershipModule(shutdownHooks), state, config, metrics)
    taskTracker = taskTrackerModule.taskTracker
    stateOpProcessor = taskTrackerModule.stateOpProcessor
  }

  test("SerializeAndDeserialize") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue

    val deserializedTask = taskTracker.instance(sampleTask.taskId).futureValue

    deserializedTask should not be empty
    deserializedTask should equal(Some(Instance(sampleTask)))
  }

  test("List") {
    testList(_.instancesBySpecSync)
  }

  test("List Async") {
    testList(_.instancesBySpec().futureValue)
  }

  private[this] def testList(call: InstanceTracker => InstanceTracker.InstancesBySpec): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")
    val task2 = makeSampleTask(TEST_APP_NAME / "b")
    val task3 = makeSampleTask(TEST_APP_NAME / "b")

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task1)).futureValue
    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task2)).futureValue
    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task3)).futureValue

    val testAppTasks = call(taskTracker)

    testAppTasks.allSpecIdsWithInstances should be(Set(TEST_APP_NAME / "a", TEST_APP_NAME / "b"))

    testAppTasks.instancesMap(TEST_APP_NAME / "a").specId should equal(TEST_APP_NAME / "a")
    testAppTasks.instancesMap(TEST_APP_NAME / "b").specId should equal(TEST_APP_NAME / "b")
    testAppTasks.instancesMap(TEST_APP_NAME / "a").instances should have size 1
    testAppTasks.instancesMap(TEST_APP_NAME / "b").instances should have size 2
    testAppTasks.instancesMap(TEST_APP_NAME / "a").instanceMap.keySet should equal(Set(Instance.Id(task1.taskId)))
    testAppTasks.instancesMap(TEST_APP_NAME / "b").instanceMap.keySet should equal(Set(Instance.Id(task2.taskId), Instance.Id(task3.taskId)))
  }

  test("GetTasks") {
    testGetTasks(_.specInstancesSync(TEST_APP_NAME))
  }

  test("GetTasks Async") {
    testGetTasks(_.specInstances(TEST_APP_NAME).futureValue)
  }

  private[this] def testGetTasks(call: InstanceTracker => Iterable[Instance]): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME)
    val task2 = makeSampleTask(TEST_APP_NAME)
    val task3 = makeSampleTask(TEST_APP_NAME)

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task1)).futureValue
    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task2)).futureValue
    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task3)).futureValue

    val testAppTasks = call(taskTracker)

    shouldContainTask(testAppTasks.toSet, task1)
    shouldContainTask(testAppTasks.toSet, task2)
    shouldContainTask(testAppTasks.toSet, task3)
    assert(testAppTasks.size == 3)
  }

  test("Count") {
    testCount(_.countLaunchedSpecInstancesSync(_))
  }

  test("Count Async") {
    testCount(_.countSpecInstances(_).futureValue)
  }

  private[this] def testCount(count: (InstanceTracker, PathId) => Int): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task1)).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(1)
    count(taskTracker, TEST_APP_NAME / "b") should be(0)
  }

  test("Contains") {
    testContains(_.hasSpecInstancesSync(_))
  }

  test("Contains Async") {
    testContains(_.hasSpecInstances(_).futureValue)
  }

  private[this] def testContains(count: (InstanceTracker, PathId) => Boolean): Unit = {
    val task1 = makeSampleTask(TEST_APP_NAME / "a")

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(task1)).futureValue

    count(taskTracker, TEST_APP_NAME / "a") should be(true)
    count(taskTracker, TEST_APP_NAME / "b") should be(false)
  }

  test("TaskLifecycle") {
    val sampleTask = MarathonTestHelper.startingTaskForApp(TEST_APP_NAME)

    // CREATE TASK
    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue

    shouldContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)

    // TASK STATUS UPDATE
    val startingTaskStatus = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, TaskState.TASK_STARTING), clock.now())

    stateOpProcessor.process(startingTaskStatus).futureValue

    shouldContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)
    taskTracker.specInstancesSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, startingTaskStatus))

    // TASK RUNNING
    val runningTaskStatus = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, TaskState.TASK_RUNNING), clock.now())

    stateOpProcessor.process(runningTaskStatus).futureValue

    shouldContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)
    taskTracker.specInstancesSync(TEST_APP_NAME).foreach(task => shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK STILL RUNNING
    val updatedRunningTaskStatus = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, TaskState.TASK_RUNNING), clock.now())
    stateOpProcessor.process(updatedRunningTaskStatus).futureValue
    shouldContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    taskTracker.specInstancesSync(TEST_APP_NAME).headOption.foreach(task =>
      shouldHaveTaskStatus(task, runningTaskStatus))

    // TASK TERMINATED
    stateOpProcessor.process(InstanceStateOp.ForceExpunge(sampleTask.taskId)).futureValue
    stateShouldNotContainKey(state, sampleTask.taskId)

    // APP SHUTDOWN
    assert(!taskTracker.hasSpecInstancesSync(TEST_APP_NAME), "App was not removed")

    // ERRONEOUS MESSAGE, TASK DOES NOT EXIST ANYMORE
    val erroneousStatus = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, TaskState.TASK_LOST), clock.now())

    val failure = stateOpProcessor.process(erroneousStatus).failed.futureValue
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
    val terminalStatusUpdate = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, taskState), clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    shouldContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    stateShouldContainKey(state, sampleTask.taskId)

    stateOpProcessor.process(terminalStatusUpdate).futureValue

    shouldNotContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
    stateShouldNotContainKey(state, sampleTask.taskId)
  }

  test("UnknownTasks") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)

    // don't call taskTracker.created, but directly running
    val runningTaskStatus = InstanceStateOp.MesosUpdate(sampleTask, makeTaskStatus(sampleTask, TaskState.TASK_RUNNING), clock.now())
    val res = stateOpProcessor.process(runningTaskStatus)
    res.failed.futureValue.getCause.getMessage should equal(s"${Instance.Id(sampleTask.taskId)} of app [/foo] does not exist")

    shouldNotContainTask(taskTracker.specInstancesSync(TEST_APP_NAME), sampleTask)
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

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app1_task1)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app1_task1, makeTaskStatus(app1_task1, TaskState.TASK_RUNNING), clock.now())).futureValue

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app1_task2)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app1_task2, makeTaskStatus(app1_task2, TaskState.TASK_RUNNING), clock.now())).futureValue

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app2_task1)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app2_task1, makeTaskStatus(app2_task1, TaskState.TASK_RUNNING), clock.now())).futureValue

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app3_task1)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app3_task1, makeTaskStatus(app3_task1, TaskState.TASK_RUNNING), clock.now())).futureValue

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app3_task2)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app3_task2, makeTaskStatus(app3_task2, TaskState.TASK_RUNNING), clock.now())).futureValue

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(app3_task3)).futureValue
    stateOpProcessor.process(InstanceStateOp.MesosUpdate(app3_task3, makeTaskStatus(app3_task3, TaskState.TASK_RUNNING), clock.now())).futureValue

    assert(state.allIds().futureValue.size == 6, "Incorrect number of tasks in state")

    val app1Tasks = taskTracker.specInstancesSync(appName1).toSet

    shouldContainTask(app1Tasks, app1_task1)
    shouldContainTask(app1Tasks, app1_task2)
    assert(app1Tasks.size == 2, "Incorrect number of tasks")

    val app2Tasks = taskTracker.specInstancesSync(appName2).toSet

    shouldContainTask(app2Tasks, app2_task1)
    assert(app2Tasks.size == 1, "Incorrect number of tasks")

    val app3Tasks = taskTracker.specInstancesSync(appName3).toSet

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
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    stateOpProcessor.process(update).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should not store if state and health did not change") {
    val sampleTask = MarathonTestHelper.healthyTask(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get
      .toBuilder
      .setTimestamp(123)
      .build()
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    stateOpProcessor.process(update).futureValue

    verify(state, times(0)).update(any())
  }

  test("Should store if state changed") {
    val sampleTask = MarathonTestHelper.stagedTaskForApp(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .build()
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_FAILED)
      .build()
    val newUpdate = InstanceStateOp.MesosUpdate(sampleTask, newStatus, clock.now())

    stateOpProcessor.process(newUpdate).futureValue

    verify(state, times(1)).delete(any())
  }

  test("Should store if health changed") {
    val sampleTask = MarathonTestHelper.runningTaskForApp(TEST_APP_NAME)
    val status = sampleTask.launched.get.status.mesosStatus.get.toBuilder
      .setHealthy(true)
      .build()
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(false)
      .build()
    val newUpdate = InstanceStateOp.MesosUpdate(sampleTask, newStatus, clock.now())

    stateOpProcessor.process(newUpdate).futureValue

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
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build()
    val newUpdate = InstanceStateOp.MesosUpdate(sampleTask, newStatus, clock.now())

    stateOpProcessor.process(newUpdate).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(sampleTask.taskId.mesosTaskId)
      .build()
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setHealthy(true)
      .build()
    val newUpdate = InstanceStateOp.MesosUpdate(sampleTask, newStatus, clock.now())

    stateOpProcessor.process(newUpdate).futureValue

    verify(state, times(1)).update(any())
  }

  test("Should store if state and health changed (no health present at first)") {
    val sampleTask = makeSampleTask(TEST_APP_NAME)
    val status = Protos.TaskStatus
      .newBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setTaskId(sampleTask.taskId.mesosTaskId)
      .build()
    val update = InstanceStateOp.MesosUpdate(sampleTask, status, clock.now())

    stateOpProcessor.process(InstanceStateOp.LaunchEphemeral(sampleTask)).futureValue
    stateOpProcessor.process(update).futureValue

    stateOpProcessor.process(update).futureValue

    reset(state)

    val newStatus = status.toBuilder
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build()
    val newUpdate = InstanceStateOp.MesosUpdate(sampleTask, newStatus, clock.now())

    stateOpProcessor.process(newUpdate).futureValue

    verify(state, times(1)).update(any())
  }

  def makeSampleTask(appId: PathId) = {
    import MarathonTestHelper.Implicits._
    MarathonTestHelper
      .stagedTaskForApp(appId)
      .withAgentInfo(_.copy(host = "host", attributes = Seq(TextAttribute("attr1", "bar"))))
      .withHostPorts(Seq(999))
  }

  def makeTaskStatus(task: Task, state: TaskState = TaskState.TASK_RUNNING) = {
    TaskStatus.newBuilder
      .setTaskId(task.taskId.mesosTaskId)
      .setState(state)
      .build
  }

  def containsTask(tasks: Iterable[Instance], task: Instance) =
    tasks.exists(t => t.instanceId == task.instanceId
      && t.agentInfo.host == task.agentInfo.host
      && t.tasks.flatMap(_.launched.map(_.hostPorts)) == task.tasks.flatMap(_.launched.map(_.hostPorts)))
  def shouldContainTask(tasks: Iterable[Instance], task: Instance) =
    assert(containsTask(tasks, task), s"Should contain ${task.instanceId}")
  def shouldNotContainTask(tasks: Iterable[Instance], task: Instance) =
    assert(!containsTask(tasks, task), s"Should not contain ${task.instanceId}")

  def shouldHaveTaskStatus(task: Instance, stateOp: InstanceStateOp.MesosUpdate) {
    assert(Option(stateOp.mesosStatus).isDefined, "mesos status is None")
    assert(task.isLaunched)
    assert(
      task.tasks.map(_.launched.get.status.mesosStatus.get).forall(status => status == stateOp.mesosStatus),
      s"Should have task status ${stateOp.mesosStatus}")
  }

  def stateShouldNotContainKey(state: PersistentStore, key: Instance.Id) {
    val keyWithPrefix = TaskEntityRepository.storePrefix + key.idString
    assert(!state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was found in state")
  }

  def stateShouldContainKey(state: PersistentStore, key: Instance.Id) {
    val keyWithPrefix = TaskEntityRepository.storePrefix + key.idString
    assert(state.allIds().futureValue.toSet.contains(keyWithPrefix), s"Key $keyWithPrefix was not found in state")
  }
}
