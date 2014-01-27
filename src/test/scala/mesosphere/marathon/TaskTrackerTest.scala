package mesosphere.marathon

import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.junit.Test
import org.apache.mesos.state.InMemoryState
import mesosphere.marathon.Protos.MarathonTask
import org.apache.mesos.Protos.Value.Text
import org.apache.mesos.Protos.{TaskID, Attribute, TaskStatus, TaskState}
import java.io.{ByteArrayInputStream, ObjectInputStream, ByteArrayOutputStream, ObjectOutputStream}

import org.junit.Assert._
import mesosphere.marathon.tasks.{TaskTracker, TaskIDUtil}
import com.google.common.collect.Lists

class TaskTrackerTest extends AssertionsForJUnit with MockitoSugar {

  def makeSampleTask(id: String) = {
    MarathonTask.newBuilder()
      .setHost("host")
      .addAllPorts(Lists.newArrayList(999))
      .setId(id)
      .addAttributes(
        Attribute.newBuilder()
        .setName("attr1")
        .setText(Text.newBuilder()
        .setValue("bar"))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
      .build()
  }

  def makeTask(id: String, host: String, port: Int) = {
    MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(port))
      .setId(id)
      .addAttributes(
      Attribute.newBuilder()
        .setName("attr1")
        .setText(Text.newBuilder()
        .setValue("bar"))
        .setType(org.apache.mesos.Protos.Value.Type.TEXT)
        .build())
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

  @Test
  def testStartingPersistsTasks() {
    def shouldContainTask(tasks: Iterable[MarathonTask], task: MarathonTask) {
      assertTrue(
        s"Should contain task ${task.getId}",
        tasks.exists(t => t.getId == task.getId
          && t.getHost == task.getHost
          && t.getPortsList == task.getPortsList)
      )
    }

    val state = new InMemoryState
    val taskTracker = new TaskTracker(state)
    val app = "foo"
    val taskId1 = TaskIDUtil.taskId(app, 1)
    val taskId2 = TaskIDUtil.taskId(app, 2)
    val taskId3 = TaskIDUtil.taskId(app, 3)

    val task1 = makeTask(taskId1, "foo.bar.bam", 100)
    val task2 = makeTask(taskId2, "foo.bar.bam", 200)
    val task3 = makeTask(taskId3, "foo.bar.bam", 300)

    taskTracker.starting(app, task1)
    taskTracker.running(app, makeTaskStatus(taskId1))

    taskTracker.starting(app, task2)
    taskTracker.running(app, makeTaskStatus(taskId2))

    taskTracker.starting(app, task3)
    taskTracker.running(app, makeTaskStatus(taskId3))

    val results = taskTracker.fetchApp(app).tasks

    shouldContainTask(results, task1)
    shouldContainTask(results, task2)
    shouldContainTask(results, task3)
  }

  @Test
  def testStoreFetchTasks() {
    def shouldContainTask(tasks: Iterable[MarathonTask], task: MarathonTask) {
      assertTrue(
        s"Should contain task ${task.getId}",
        tasks.exists(t => t.getId == task.getId
          && t.getHost == task.getHost
          && t.getPortsList == task.getPortsList)
      )
    }

    val state = new InMemoryState
    val taskTracker1 = new TaskTracker(state)
    val app = "foo"
    val taskId1 = TaskIDUtil.taskId(app, 1)
    val taskId2 = TaskIDUtil.taskId(app, 2)
    val taskId3 = TaskIDUtil.taskId(app, 3)

    val task1 = makeTask(taskId1, "foo.bar.bam", 100)
    val task2 = makeTask(taskId2, "foo.bar.bam", 200)
    val task3 = makeTask(taskId3, "foo.bar.bam", 300)

    taskTracker1.starting(app, task1)
    taskTracker1.running(app, makeTaskStatus(taskId1))

    taskTracker1.starting(app, task2)
    taskTracker1.running(app, makeTaskStatus(taskId2))

    taskTracker1.starting(app, task3)
    taskTracker1.running(app, makeTaskStatus(taskId3))

    val taskTracker2 = new TaskTracker(state)
    val results = taskTracker2.fetchApp(app).tasks

    shouldContainTask(results, task1)
    shouldContainTask(results, task2)
    shouldContainTask(results, task3)
  }

  @Test
  def testSerDe() {
    val state = new InMemoryState
    val taskTracker = new TaskTracker(state)

    val task1 = makeSampleTask("task1")
    val task2 = makeSampleTask("task2")
    val task3 = makeSampleTask("task3")

    taskTracker.starting("app1", task1)
    taskTracker.starting("app1", task2)
    taskTracker.starting("app1", task3)

    {
      val baos = new ByteArrayOutputStream()
      baos.flush()
      val oos = new ObjectOutputStream(baos)
      taskTracker.serialize("app1", taskTracker.get("app1"), oos)

      val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
      taskTracker.deserialize("app1", ois)
      assertTrue("Tasks are not properly serialized", taskTracker.count("app1") == 3)
    }

    taskTracker.running("app1", makeTaskStatus("task1"))
    taskTracker.running("app1", makeTaskStatus("task2"))
    taskTracker.running("app1", makeTaskStatus("task3"))

    {
      val baos = new ByteArrayOutputStream()
      baos.flush()
      val oos = new ObjectOutputStream(baos)
      taskTracker.serialize("app1", taskTracker.get("app1"), oos)

      val ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
      taskTracker.deserialize("app1", ois)
      assertTrue("Tasks are not properly serialized", taskTracker.count("app1") == 3)
    }
  }
}
