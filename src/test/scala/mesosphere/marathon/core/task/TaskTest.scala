package mesosphere.marathon
package core.task

import java.util.UUID

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.PrefixInstance
import mesosphere.marathon.core.instance.{Instance, LocalVolumeId, TestTaskBuilder}
import mesosphere.marathon.core.pod.{ContainerNetwork, HostNetwork}
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.{NetworkInfo, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.{AppDefinition, PathId, PortDefinition}
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{Protos => MesosProtos}
import org.scalatest.Inside
import play.api.libs.json._

// TODO(cleanup): remove most of the test cases into a NetworkInTest
import scala.concurrent.duration._

class TaskTest extends UnitTest with Inside {

  class Fixture {

    val clock = new SettableClock()

    val appWithoutIpAddress = AppDefinition(id = PathId("/foo/bar"), networks = Seq(HostNetwork), portDefinitions = Seq(PortDefinition(0)))
    val appVirtualNetworks = Seq(ContainerNetwork("whatever"))
    val appWithIpAddress = AppDefinition(
      id = PathId("/foo/bar"),
      portDefinitions = Seq.empty,
      networks = appVirtualNetworks
    )

    val networkWithoutIp = MesosProtos.NetworkInfo.newBuilder.build()

    val ipString1 = "123.123.123.123"
    val ipAddress1 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipString1).build()

    val ipString2 = "123.123.123.124"
    val ipAddress2 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipString2).build()

    val networkWithOneIp1 = MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ipAddress1).build()
    val networkWithOneIp2 = MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ipAddress2).build()

    val networkWithMultipleIps = MesosProtos.NetworkInfo.newBuilder.addAllIpAddresses(Seq(ipAddress1, ipAddress2).asJava).build()

    val host: String = "agent1.mesos"

    val taskWithoutIp =
      TestTaskBuilder.Helper
        .runningTaskForApp(appWithoutIpAddress.id)

    def taskWithOneIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworksAndOneIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworkAndNoIp: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithoutIp).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithOneNetworkAndMultipleIPs: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithMultipleIps).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }

    def taskWithMultipleNetworkAndMultipleIPs: Task = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1, networkWithOneIp2).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName = host, hostPorts = Nil, ipAddresses = ipAddresses)))
    }
  }

  "Task" should {
    "effectiveIpAddress returns the container ip for MarathonTask instances with one NetworkInfo (if the app requests an IP)" in {
      val f = new Fixture
      f.taskWithOneIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress).value should equal(f.ipString1)
    }

    "effectiveIpAddress returns the first container ip for for MarathonTask instances with multiple NetworkInfos (if the app requests an IP)" in {
      val f = new Fixture
      f.taskWithMultipleNetworksAndOneIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress).value should equal (f.ipString1)
    }

    "effectiveIpAddress returns None if there is no ip" in {
      val f = new Fixture
      f.taskWithMultipleNetworkAndNoIp.status.networkInfo.effectiveIpAddress(f.appWithIpAddress) should be (None)
    }

    "effectiveIpAddress returns the agent ip for MarathonTask instances with one NetworkInfo (if the app does NOT request an IP)" in {
      val f = new Fixture
      f.taskWithOneIp.status.networkInfo.effectiveIpAddress(f.appWithoutIpAddress).value should equal(f.host)
    }

    "ipAddresses returns None for MarathonTask instances with no IPs" in {
      val f = new Fixture
      f.taskWithoutIp.status.networkInfo.ipAddresses should be (Nil)
    }

    "ipAddresses returns an empty list for MarathonTask instances with no IPs and multiple NetworkInfos" in {
      val f = new Fixture
      f.taskWithMultipleNetworkAndNoIp.status.networkInfo.ipAddresses should be (empty)
    }

    "ipAddresses returns all IPs for MarathonTask instances with multiple IPs" in {
      val f = new Fixture
      f.taskWithMultipleNetworkAndMultipleIPs.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1, f.ipAddress2))
    }

    "ipAddresses returns all IPs for MarathonTask instances with multiple IPs and multiple NetworkInfos" in {
      val f = new Fixture
      f.taskWithMultipleNetworkAndMultipleIPs.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1, f.ipAddress2))
    }

    "ipAddresses returns one IP for MarathonTask instances with one IP and one NetworkInfo" in {
      val f = new Fixture
      f.taskWithOneIp.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1))
    }

    "ipAddresses returns one IP for MarathonTask instances with one IP and multiple NetworkInfo" in {
      val f = new Fixture
      f.taskWithMultipleNetworksAndOneIp.status.networkInfo.ipAddresses should equal(Seq(f.ipAddress1))
    }

    "VolumeId should be parsable, even if the task contains a dot in the appId" in {
      val volumeIdString = "registry.domain#storage#8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1"
      val volumeId = LocalVolumeId.unapply(volumeIdString)
      volumeId should not be None
      volumeId should be (Some(LocalVolumeId(PathId.fromSafePath("registry.domain"), "storage", "8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1")))
    }

    "isUnreachableExpired returns if task is inactive" in {
      val f = new Fixture

      val condition = Condition.Unreachable
      val instanceId = Instance.Id.forRunSpec(f.appWithIpAddress.id)
      val taskId = Task.Id(instanceId)
      val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(condition, taskId, f.clock.now - 5.minutes)
      val task = TestTaskBuilder.Helper.minimalTask(taskId, f.clock.now - 5.minutes, mesosStatus, condition)

      task.isUnreachableExpired(f.clock.now, 4.minutes) should be(true)
      task.isUnreachableExpired(f.clock.now, 10.minutes) should be(false)
    }

    "a reserved task updates network info on MesosUpdate" in {
      val f = new Fixture

      val condition = Condition.Running
      val instanceId = Instance.Id.forRunSpec(f.appWithIpAddress.id)
      val taskId = Task.Id(instanceId)
      val status = Task.Status(
        stagedAt = f.clock.now,
        startedAt = Some(f.clock.now),
        mesosStatus = None, condition, NetworkInfoPlaceholder())
      val task = Task(taskId, f.clock.now, status)
      val instance = mock[Instance]
      instance.hasReservation returns true

      val containerStatus = MarathonTestHelper.containerStatusWithNetworkInfo(f.networkWithOneIp1)

      val mesosStatus = MesosProtos.TaskStatus.newBuilder
        .setTaskId(taskId.mesosTaskId)
        .setState(MesosProtos.TaskState.TASK_RUNNING)
        .setContainerStatus(containerStatus)
        .setTimestamp(f.clock.now.millis.toDouble).build()

      When("task is launched, no ipAddress should be found")
      task.status.networkInfo.ipAddresses shouldBe Nil

      Then("MesosUpdate TASK_RUNNING is applied with containing NetworkInfo")
      inside(task.update(instance, Condition.Running, mesosStatus, f.clock.now)) {
        case effect: TaskUpdateEffect.Update =>
          Then("NetworkInfo should be updated")
          effect.newState.status.networkInfo.ipAddresses shouldBe Seq(f.ipAddress1)
      }
    }

    "a task that was not running before and is updated to running updates network info" in {
      val f = new Fixture

      val condition = Condition.Staging
      val instanceId = Instance.Id.forRunSpec(f.appWithIpAddress.id)
      val taskId = Task.Id(instanceId)
      val status = Task.Status(
        stagedAt = f.clock.now,
        startedAt = None,
        mesosStatus = None, condition, NetworkInfoPlaceholder())
      val task = Task(taskId, f.clock.now, status)
      val instance = mock[Instance]
      instance.hasReservation returns true

      val containerStatus = MarathonTestHelper.containerStatusWithNetworkInfo(f.networkWithOneIp1)

      val mesosStatus = MesosProtos.TaskStatus.newBuilder
        .setTaskId(taskId.mesosTaskId)
        .setState(MesosProtos.TaskState.TASK_RUNNING)
        .setContainerStatus(containerStatus)
        .setTimestamp(f.clock.now.millis.toDouble).build()

      When("task is launched, no ipAddress should be found")
      task.status.networkInfo.ipAddresses shouldBe Nil

      Then("MesosUpdate TASK_RUNNING is applied with containing NetworkInfo")
      inside(task.update(instance, Condition.Running, mesosStatus, f.clock.now)) {
        case effect: TaskUpdateEffect.Update =>
          Then("NetworkInfo should be updated")
          effect.newState.status.networkInfo.ipAddresses shouldBe Seq(f.ipAddress1)
      }
    }

    "Task.Id as key in Map" in {
      val instanceId = Instance.Id(PathId("/my/app"), PrefixInstance, UUID.randomUUID())
      val taskId1 = Task.EphemeralTaskId(instanceId, Some("rails"))
      val taskId2 = Task.EphemeralTaskId(instanceId, Some("mysql"))

      val m = Map(taskId1 -> 1)

      m.get(taskId1) should be('defined)
      m.get(taskId2) should not be 'defined
      m(taskId1) should be(1)
      an[NoSuchElementException] should be thrownBy m(taskId2)
    }
  }

  "json serialization" should {
    "round trip serialize a Task" in {
      val f = new Fixture
      val task: Task = TestTaskBuilder.Helper.minimalRunning(
        f.appWithoutIpAddress.id, Condition.Running, f.clock.now)
      Json.toJson(task).as[Task] shouldBe task
    }
  }

}
