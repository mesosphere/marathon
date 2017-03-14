package mesosphere.marathon
package core.task

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.state.{ NetworkInfo, NetworkInfoPlaceholder }
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.update.{ TaskUpdateEffect, TaskUpdateOperation }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.state.{ AppDefinition, IpAddress, PathId }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.OptionValues._
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import org.scalatest.Inside
import play.api.libs.json._

// TODO(cleanup): remove most of the test cases into a NetworkInTest
import scala.concurrent.duration._

class TaskTest extends FunSuite with Mockito with GivenWhenThen with Matchers with Inside {

  class Fixture {

    val clock = ConstantClock()

    val appWithoutIpAddress = AppDefinition(id = PathId("/foo/bar"), ipAddress = None)
    val appWithIpAddress = AppDefinition(
      id = PathId("/foo/bar"),
      portDefinitions = Seq.empty,
      ipAddress = Some(IpAddress()))

    val networkWithoutIp = MesosProtos.NetworkInfo.newBuilder.build()

    val ipString1 = "123.123.123.123"
    val ipAddress1 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipString1).build()

    val ipString2 = "123.123.123.124"
    val ipAddress2 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipString2).build()

    val networkWithOneIp1 = MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ipAddress1).build()
    val networkWithOneIp2 = MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ipAddress2).build()

    val networkWithMultipleIps = MesosProtos.NetworkInfo.newBuilder.addAllIpAddresses(Seq(ipAddress1, ipAddress2)).build()

    val host: String = "agent1.mesos"

    val taskWithoutIp =
      TestTaskBuilder.Helper
        .runningTaskForApp(appWithoutIpAddress.id)

    def taskWithOneIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworksAndOneIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworkAndNoIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithoutIp).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithOneNetworkAndMultipleIPs: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithMultipleIps).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworkAndMultipleIPs: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1, networkWithOneIp2).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }
  }

  test("effectiveIpAddress returns the container ip for MarathonTask instances with one NetworkInfo (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithOneIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress).value should equal(f.ipString1)
  }

  test("effectiveIpAddress returns the first container ip for for MarathonTask instances with multiple NetworkInfos (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress).value should equal (f.ipString1)
  }

  test("effectiveIpAddress returns None if there is no ip") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress) should be (None)
  }

  test("effectiveIpAddress returns the agent ip for MarathonTask instances with one NetworkInfo (if the app does NOT request an IP)") {
    val f = new Fixture
    f.taskWithOneIp.status.networkInfo.effectiveIpAddress(f.appWithoutIpAddress).value should equal(f.host)
  }

  test("ipAddresses returns None for MarathonTask instances with no IPs") {
    val f = new Fixture
    f.taskWithoutIp.status.networkInfo.ipAddresses should be (Nil)
  }

  test("ipAddresses returns an empty list for MarathonTask instances with no IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp.status.networkInfo.ipAddresses should be (empty)
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and one NetworkInfo") {
    val f = new Fixture
    f.taskWithOneIp.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and multiple NetworkInfo") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1))
  }

  test("VolumeId should be parsable, even if the task contains a dot in the appId") {
    val volumeIdString = "registry.domain#storage#8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1"
    val volumeId = LocalVolumeId.unapply(volumeIdString)
    volumeId should not be None
    volumeId should be (Some(LocalVolumeId(PathId.fromSafePath("registry.domain"), "storage", "8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1")))
  }

  test("isUnreachableExpired returns if task is inactive") {
    val f = new Fixture

    val condition = Condition.Unreachable
    val taskId = Task.Id.forRunSpec(f.appWithIpAddress.id)
    val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(condition, taskId, f.clock.now - 5.minutes)
    val task = TestTaskBuilder.Helper.minimalTask(taskId, f.clock.now - 5.minutes, mesosStatus, condition)

    task.isUnreachableExpired(f.clock.now, 4.minutes) should be(true)
    task.isUnreachableExpired(f.clock.now, 10.minutes) should be(false)
  }

  test("a reserved task throws an exception on MesosUpdate") {
    val f = new Fixture

    val condition = Condition.Reserved
    val taskId = Task.Id.forRunSpec(f.appWithIpAddress.id)
    val reservation = mock[Task.Reservation]
    val status = Task.Status(f.clock.now, None, None, condition, NetworkInfoPlaceholder())
    val task = Task.Reserved(taskId, reservation, status, f.clock.now)

    val mesosStatus = MesosTaskStatusTestHelper.running(taskId)
    val op = TaskUpdateOperation.MesosUpdate(Condition.Running, mesosStatus, f.clock.now)

    inside(task.update(op)) {
      case effect: TaskUpdateEffect.Update =>
        effect.newState shouldBe a[Task.LaunchedOnReservation]
    }
  }

  test("a LaunchedOnReservation task updates network info on MesosUpdate") {
    val f = new Fixture

    val condition = Condition.Running
    val taskId = Task.Id.forRunSpec(f.appWithIpAddress.id)
    val reservation = mock[Task.Reservation]
    val status = Task.Status(
      stagedAt = f.clock.now,
      startedAt = Some(f.clock.now),
      mesosStatus = None, condition, NetworkInfoPlaceholder())
    val task = Task.LaunchedOnReservation(taskId, f.clock.now, status, reservation)

    val containerStatus = MarathonTestHelper.containerStatusWithNetworkInfo(f.networkWithOneIp1)

    val mesosStatus = MesosProtos.TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(MesosProtos.TaskState.TASK_RUNNING)
      .setContainerStatus(containerStatus)
      .setTimestamp(f.clock.now.millis.toDouble).build()
    val op = TaskUpdateOperation.MesosUpdate(Condition.Running, mesosStatus, f.clock.now)

    When("task is launched, no ipAddress should be found")
    task.status.networkInfo.ipAddresses shouldBe Nil

    Then("MesosUpdate TASK_RUNNING is applied with containing NetworkInfo")
    inside(task.update(op)) {
      case effect: TaskUpdateEffect.Update =>
        Then("NetworkInfo should be updated")
        effect.newState.status.networkInfo.ipAddresses shouldBe Seq(f.ipAddress1)
    }
  }

  test("a task that was not running before and is updated to running updates network info") {
    val f = new Fixture

    val condition = Condition.Staging
    val taskId = Task.Id.forRunSpec(f.appWithIpAddress.id)
    val reservation = mock[Task.Reservation]
    val status = Task.Status(
      stagedAt = f.clock.now,
      startedAt = None,
      mesosStatus = None, condition, NetworkInfoPlaceholder())
    val task = Task.LaunchedOnReservation(taskId, f.clock.now, status, reservation)

    val containerStatus = MarathonTestHelper.containerStatusWithNetworkInfo(f.networkWithOneIp1)

    val mesosStatus = MesosProtos.TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(MesosProtos.TaskState.TASK_RUNNING)
      .setContainerStatus(containerStatus)
      .setTimestamp(f.clock.now.millis.toDouble).build()
    val op = TaskUpdateOperation.MesosUpdate(Condition.Running, mesosStatus, f.clock.now)

    When("task is launched, no ipAddress should be found")
    task.status.networkInfo.ipAddresses shouldBe Nil

    Then("MesosUpdate TASK_RUNNING is applied with containing NetworkInfo")
    inside(task.update(op)) {
      case effect: TaskUpdateEffect.Update =>
        Then("NetworkInfo should be updated")
        effect.newState.status.networkInfo.ipAddresses shouldBe Seq(f.ipAddress1)
    }
  }

  test("a reserved task returns an update") {
    val f = new Fixture

    val condition = Condition.Reserved
    val taskId = Task.Id.forRunSpec(f.appWithIpAddress.id)
    val reservation = mock[Task.Reservation]
    val status = Task.Status(f.clock.now, None, None, condition, NetworkInfoPlaceholder())
    val task = Task.Reserved(taskId, reservation, status, f.clock.now)

    val op = TaskUpdateOperation.LaunchOnReservation(f.clock.now, status)

    val effect = task.update(op)

    effect shouldBe a[TaskUpdateEffect.Update]
  }

  test("Task.Id as key in Map") {
    val taskId1 = Task.Id("foobar")
    val taskId2 = Task.Id("baz")

    val m = Map(taskId1 -> 1)

    m.get(taskId1) should be('defined)
    m.get(taskId2) should not be 'defined
    m(taskId1) should be(1)
    an[NoSuchElementException] should be thrownBy m(taskId2)
  }

  test("json serialization round trip serialize a LaunchedEphemeral task") {
    val f = new Fixture
    val launchedEphemeral: Task.LaunchedEphemeral = TestTaskBuilder.Helper.minimalRunning(
      f.appWithoutIpAddress.id, Condition.Running, f.clock.now)
    Json.toJson(launchedEphemeral).as[Task] shouldBe launchedEphemeral
  }

  test("json serialization round trip serialize a Reserved task") {
    val f = new Fixture
    val reservedTask: Task.Reserved = TestTaskBuilder.Helper.residentReservedTask(
      f.appWithoutIpAddress.id,
      taskReservationState = Task.Reservation.State.New(None),
      LocalVolumeId(f.appWithIpAddress.id, "very-path", "deadbeef-1234-0000-0000-000000000000"),
      LocalVolumeId(f.appWithIpAddress.id, "very-path", "deadbeef-5678-0000-0000-000000000000"))

    Json.toJson(reservedTask).as[Task] shouldBe reservedTask
  }

  test("json serialization round trip serialize a LaunchedOnReservation task") {
    val f = new Fixture
    val launchedTask: Task.LaunchedOnReservation = TestTaskBuilder.Helper.residentLaunchedTask(
      f.appWithoutIpAddress.id,
      LocalVolumeId(f.appWithIpAddress.id, "very-path", "deadbeef-1234-0000-0000-000000000000"),
      LocalVolumeId(f.appWithIpAddress.id, "very-path", "deadbeef-5678-0000-0000-000000000000"))

    Json.toJson(launchedTask).as[Task] shouldBe launchedTask
  }
}
