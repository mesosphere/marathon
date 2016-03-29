package mesosphere.marathon.core.task

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.{ AppDefinition, IpAddress, PathId }
import mesosphere.marathon.test.Mockito
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import org.scalatest.OptionValues._

class TaskTest extends FunSuite with Mockito with GivenWhenThen with Matchers {
  import scala.collection.JavaConverters._

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

    val networkWithMultipleIps = MesosProtos.NetworkInfo.newBuilder.addAllIpAddresses(Seq(ipAddress1, ipAddress2).asJava).build()

    val host: String = "agent1.mesos"

    import MarathonTestHelper.Implicits._

    val taskWithoutIp =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))

    val taskWithOneIp =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))
        .withNetworkInfos(Seq(networkWithOneIp1))

    val taskWithMultipleNetworksAndOneIp =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))
        .withNetworkInfos(Seq(networkWithoutIp, networkWithOneIp1))

    val taskWithMultipleNetworkAndNoIp =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))
        .withNetworkInfos(Seq(networkWithoutIp, networkWithoutIp))

    val taskWithOneNetworkAndMultipleIPs =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))
        .withNetworkInfos(Seq(networkWithMultipleIps))

    val taskWithMultipleNetworkAndMultipleIPs =
      MarathonTestHelper
        .runningTaskForApp(appWithoutIpAddress.id)
        .withAgentInfo(_.copy(host = host))
        .withNetworkInfos(Seq(networkWithOneIp1, networkWithOneIp2))
  }

  test("effectiveIpAddress returns the container ip for MarathonTask instances with one NetworkInfo (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithOneIp.effectiveIpAddress(f.appWithIpAddress).value should equal(f.ipString1)
  }

  test("effectiveIpAddress returns the first container ip for for MarathonTask instances with multiple NetworkInfos (if the app requests an IP)") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp.effectiveIpAddress(f.appWithIpAddress).value should equal (f.ipString1)
  }

  test("effectiveIpAddress returns None if there is no ip") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp.effectiveIpAddress(f.appWithIpAddress) should be (None)
  }

  test("effectiveIpAddress returns the agent ip for MarathonTask instances with one NetworkInfo (if the app does NOT request an IP)") {
    val f = new Fixture
    f.taskWithOneIp.effectiveIpAddress(f.appWithoutIpAddress).value should equal(f.host)
  }

  test("ipAddresses returns None for MarathonTask instances with no IPs") {
    val f = new Fixture
    f.taskWithoutIp.launched.value.ipAddresses should be (None)
  }

  test("ipAddresses returns an empty list for MarathonTask instances with no IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndNoIp.launched.value.ipAddresses.value should be (empty)
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs.launched.value.ipAddresses.value should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs and multiple NetworkInfos") {
    val f = new Fixture
    f.taskWithMultipleNetworkAndMultipleIPs.launched.value.ipAddresses.value should equal(Seq(f.ipAddress1, f.ipAddress2))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and one NetworkInfo") {
    val f = new Fixture
    f.taskWithOneIp.launched.value.ipAddresses.value should equal(Seq(f.ipAddress1))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and multiple NetworkInfo") {
    val f = new Fixture
    f.taskWithMultipleNetworksAndOneIp.launched.value.ipAddresses.value should equal(Seq(f.ipAddress1))
  }

}
