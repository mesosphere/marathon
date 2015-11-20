package mesosphere.marathon.tasks

import scala.collection.JavaConverters._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos._
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

class MarathonTasksTest extends MarathonSpec with Matchers {

  class Fixture {

    val networkWithoutIp = mesos.NetworkInfo.newBuilder.build()

    val ip1 = mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123").build()

    val ip2 = mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.124").build()

    val networkWithOneIp1 = mesos.NetworkInfo.newBuilder.addIpAddresses(ip1).build()
    val networkWithOneIp2 = mesos.NetworkInfo.newBuilder.addIpAddresses(ip2).build()

    val networkWithMultipleIps = mesos.NetworkInfo.newBuilder.addAllIpAddresses(Seq(ip1, ip2).asJava).build()

    val taskWithoutIp = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .build

    val taskWithOneIp = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(networkWithOneIp1)
      .build

    val taskWithMultipleNetworksAndOneIp = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder
        .setValue("abcd-1234"))
      .addNetworks(networkWithoutIp)
      .addNetworks(networkWithOneIp1)
      .build

    val taskWithMultipleNetworkAndNoIp = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(networkWithoutIp)
      .addNetworks(networkWithoutIp)
      .build

    val taskWithOneNetworkAndMultipleIPs = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(networkWithMultipleIps)
      .build

    val taskWithMultipleNetworkAndMultipleIPs = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(networkWithOneIp1)
      .addNetworks(networkWithOneIp2)
      .build
  }

  test("Compute effectiveIpAddress for MarathonTask instances without their own IP addresses") {
    val f = new Fixture
    MarathonTasks.effectiveIpAddress(f.taskWithoutIp) should equal ("agent1.mesos")
  }

  test("Compute effectiveIpAddress for MarathonTask instances with one NetworkInfo") {
    val f = new Fixture
    MarathonTasks.effectiveIpAddress(f.taskWithOneIp) should equal ("123.123.123.123")
  }

  test("Compute effectiveIpAddress for MarathonTask instances with multiple NetworkInfos") {
    val f = new Fixture
    MarathonTasks.effectiveIpAddress(f.taskWithMultipleNetworksAndOneIp) should equal ("123.123.123.123")
    MarathonTasks.effectiveIpAddress(f.taskWithMultipleNetworkAndNoIp) should equal ("agent1.mesos")
  }

  test("ipAddresses returns an empty list for MarathonTask instances with no IPs") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithoutIp) should be (empty)
  }

  test("ipAddresses returns an empty list for MarathonTask instances with no IPs and multiple NetworkInfos") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithMultipleNetworkAndNoIp) should be (empty)
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithMultipleNetworkAndMultipleIPs) should equal(Seq(f.ip1, f.ip2))
  }

  test("ipAddresses returns all IPs for MarathonTask instances with multiple IPs and multiple NetworkInfos") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithMultipleNetworkAndMultipleIPs) should equal(Seq(f.ip1, f.ip2))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and one NetworkInfo") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithOneIp) should equal(Seq(f.ip1))
  }

  test("ipAddresses returns one IP for MarathonTask instances with one IP and multiple NetworkInfo") {
    val f = new Fixture
    MarathonTasks.ipAddresses(f.taskWithMultipleNetworksAndOneIp) should equal(Seq(f.ip1))
  }

}

