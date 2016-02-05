package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task
import Task.Id
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.{ Attribute, TaskStatus }
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

class TaskSerializerTest extends FunSuite with Mockito with Matchers with GivenWhenThen {
  import scala.collection.JavaConverters._

  test("minimal marathonTask => Task") {
    Given("a minimal MarathonTask")
    val marathonTask = MarathonTask.newBuilder().setId("task").setHost(sampleHost).setLaunchCounter(0).build()

    When("we convert it to task")
    val taskState = TaskSerializer.taskState(marathonTask)

    Then("we get a minimal task State")
    val expectedState = Task(
      taskId,
      Task.AgentInfo(host = sampleHost, agentId = None, attributes = Iterable.empty),
      reservationWithVolume = None,
      launchCounter = 0,
      launchedTask = None
    )

    taskState should be(expectedState)

    When("we serialize it again")
    val marathonTask2 = TaskSerializer.marathonTask(taskState)

    Then("we get the original state back")
    marathonTask2 should equal(marathonTask)
  }

  test("full marathonTask with no networking => Task") {
    Given("a MarathonTask with all fields and host ports")
    val marathonTask = completeTask

    When("we convert it to task")
    val taskState = TaskSerializer.taskState(marathonTask)

    Then("we get the expected task state")
    val expectedState = fullSampleTaskStateWithoutNetworking

    taskState should be(expectedState)

    When("we serialize it again")
    val marathonTask2 = TaskSerializer.marathonTask(taskState)

    Then("we get the original state back")
    marathonTask2 should equal(marathonTask)
  }

  test("full marathonTask with host ports => Task") {
    Given("a MarathonTask with all fields and host ports")
    val samplePorts = Iterable(80, 81)
    val marathonTask =
      completeTask.toBuilder
        .addAllPorts(samplePorts.map(Integer.valueOf(_)).asJava)
        .build()

    When("we convert it to task")
    val taskState = TaskSerializer.taskState(marathonTask)

    Then("we get the expected task state")
    val expectedState = fullSampleTaskStateWithoutNetworking.copy(
      launchedTask = fullSampleTaskStateWithoutNetworking.launchedTask.map(
        _.copy(networking = Task.HostPorts(samplePorts)
        ))
    )

    taskState should be(expectedState)

    When("we serialize it again")
    val marathonTask2 = TaskSerializer.marathonTask(taskState)

    Then("we get the original state back")
    marathonTask2 should equal(marathonTask)
  }

  test("full marathonTask with NetworkInfoList => Task") {
    Given("a MarathonTask with all fields and host ports")
    val samplePorts = Iterable(80, 81)
    val marathonTask =
      completeTask.toBuilder
        .addAllNetworks(sampleNetworks.asJava)
        .build()

    When("we convert it to task")
    println(marathonTask)
    val taskState = TaskSerializer.taskState(marathonTask)

    Then("we get the expected task state")
    val expectedState = fullSampleTaskStateWithoutNetworking.copy(
      launchedTask = fullSampleTaskStateWithoutNetworking.launchedTask.map(
        _.copy(networking = Task.NetworkInfoList(sampleNetworks)
        ))
    )

    taskState should be(expectedState)

    When("we serialize it again")
    val marathonTask2 = TaskSerializer.marathonTask(taskState)

    Then("we get the original state back")
    marathonTask2 should equal(marathonTask)
  }

  private[this] val taskId = Task.Id("task")
  private[this] val sampleHost: String = "somehost"
  private[this] val sampleAttributes: Iterable[Attribute] = Iterable(attribute("label1", "value1"))
  private[this] val stagedAtLong: Long = 1
  private[this] val startedAtLong: Long = 2
  private[this] val appVersion: Timestamp = Timestamp(3)
  private[this] val sampleTaskStatus: TaskStatus =
    MesosProtos.TaskStatus.newBuilder()
      .setTaskId(MesosProtos.TaskID.newBuilder().setValue(taskId.id))
      .setState(MesosProtos.TaskState.TASK_RUNNING)
      .build()
  private[this] val sampleSlaveId: MesosProtos.SlaveID.Builder = MesosProtos.SlaveID.newBuilder().setValue("slaveId")
  private[this] val sampleNetworks: Iterable[MesosProtos.NetworkInfo] =
    Iterable(
      MesosProtos.NetworkInfo.newBuilder()
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("1.2.3.4"))
        .build()
    )
  private[this] val fullSampleTaskStateWithoutNetworking: Task = Task(
    taskId,
    Task.AgentInfo(host = sampleHost, agentId = Some(sampleSlaveId.getValue), attributes = sampleAttributes),
    reservationWithVolume = Some(Task.ReservationWithVolume),
    launchCounter = 10,
    launchedTask = Some(
      Task.LaunchedTask(
        appVersion = appVersion,
        status = Task.TaskStatus(
          stagedAt = Timestamp(stagedAtLong),
          startedAt = Some(Timestamp(startedAtLong)),
          status = Some(sampleTaskStatus)
        ),
        networking = Task.NoNetworking
      )
    )
  )
  private[this] val completeTask =
    MarathonTask
      .newBuilder()
      .setId(taskId.id)
      .setHost(sampleHost)
      .addAllAttributes(sampleAttributes.asJava)
      .setStagedAt(stagedAtLong)
      .setStartedAt(startedAtLong)
      .setVersion(appVersion.toString)
      .setStatus(sampleTaskStatus)
      .setSlaveId(sampleSlaveId)
      .setLaunchCounter(10)
      .setReservationWithVolumeId(true)
      .build()

  private[this] def attribute(name: String, textValue: String): MesosProtos.Attribute = {
    val text = MesosProtos.Value.Text.newBuilder().setValue(textValue)
    MesosProtos.Attribute.newBuilder().setName(name).setType(MesosProtos.Value.Type.TEXT).setText(text).build()
  }
}
