package mesosphere.marathon
package core.task

import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ AppDefinition, IpAddress, PathId }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.{ MarathonTestHelper, Mockito }
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.OptionValues._
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

// TODO(cleanup): remove most of the test cases into a NetworkInTest
class TaskTest extends FunSuite with Mockito with GivenWhenThen with Matchers {

  class Fixture {
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

    import MarathonTestHelper.Implicits._

    val taskWithoutIp =
      TestTaskBuilder.Helper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))

    def taskWithOneIp(app: AppDefinition) = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName = host, hostPorts = Nil, ipAddresses = Some(ipAddresses))))
    }

    def taskWithMultipleNetworksAndOneIp(app: AppDefinition) = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithOneIp1).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName = host, hostPorts = Nil, ipAddresses = Some(ipAddresses))))
    }

    def taskWithMultipleNetworkAndNoIp(app: AppDefinition) = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithoutIp, networkWithoutIp).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName = host, hostPorts = Nil, ipAddresses = Some(ipAddresses))))
    }

    def taskWithOneNetworkAndMultipleIPs(app: AppDefinition) = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithMultipleIps).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName = host, hostPorts = Nil, ipAddresses = Some(ipAddresses))))
    }

    def taskWithMultipleNetworkAndMultipleIPs(app: AppDefinition) = {
      val ipAddresses: Seq[MesosProtos.NetworkInfo.IPAddress] = Seq(networkWithOneIp1, networkWithOneIp2).flatMap(_.getIpAddressesList)
      val t = TestTaskBuilder.Helper.runningTaskForApp(appWithoutIpAddress.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName = host, hostPorts = Nil, ipAddresses = Some(ipAddresses))))
    }
  }

  test("effectiveIpAddress returns the container ip for MarathonTask instances with one NetworkInfo (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithOneIp(f.appWithIpAddress).status.networkInfo.effectiveIpAddress.value should equal(f.ipString1)
  }

  test("effectiveIpAddress returns the first container ip for for MarathonTask instances with multiple NetworkInfos (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp(f.appWithIpAddress).status.networkInfo.effectiveIpAddress.value should equal (f.ipString1)
  }

  test("effectiveIpAddress returns None if there is no ip") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp(f.appWithIpAddress).status.networkInfo.effectiveIpAddress should be (None)
  }

  test("effectiveIpAddress returns the agent ip for MarathonTask instances with one NetworkInfo (if the app does NOT request an IP)") {
    val f = new Fixture
    f.taskWithOneIp(f.appWithoutIpAddress).status.networkInfo.effectiveIpAddress.value should equal(f.host)
  }

  test("ipAddresses returns None for MarathonTask instances with no IPs") {
    val f = new Fixture
    f.taskWithoutIp.status.networkInfo.ipAddresses should be (None)
    //    createNetworkInfo(f.taskWithoutIp, f.appWithoutIpAddress, f.host).ipAddresses should be (None)
  }

  test("ipAddresses returns an empty list for MarathonTask instances with no IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp(f.appWithoutIpAddress).status.networkInfo.ipAddresses.value should be (empty)
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs(f.appWithIpAddress).status.networkInfo.ipAddresses.value should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs(f.appWithIpAddress).status.networkInfo.ipAddresses.value should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and one NetworkInfo") {
    val f = new Fixture
    f.taskWithOneIp(f.appWithIpAddress).status.networkInfo.ipAddresses.value should equal(Seq(f.ipAddress1))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and multiple NetworkInfo") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp(f.appWithIpAddress).status.networkInfo.ipAddresses.value should equal(Seq(f.ipAddress1))
  }

  test("VolumeId should be parsable, even if the task contains a dot in the appId") {
    val volumeIdString = "registry.domain#storage#8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1"
    val volumeId = LocalVolumeId.unapply(volumeIdString)
    volumeId should not be None
    volumeId should be (Some(LocalVolumeId(PathId.fromSafePath("registry.domain"), "storage", "8e1f0af7-3fdd-11e6-a2ab-2687a99fcff1")))
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

}
