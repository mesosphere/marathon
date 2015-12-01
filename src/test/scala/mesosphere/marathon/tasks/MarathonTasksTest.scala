package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos._
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

class MarathonTasksTest extends MarathonSpec with Matchers {

  class Fixture {

    val network1 = mesos.NetworkInfo.newBuilder.build()

    val network2 = mesos.NetworkInfo.newBuilder.addIpAddresses(
      mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123")
    ).build()

    val task1 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .build

    val task2 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(network2)
      .build

    val task3 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder
        .setValue("abcd-1234"))
      .addNetworks(network1)
      .addNetworks(network2)
      .build

    val task4 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addNetworks(network1)
      .addNetworks(network1)
      .build
  }

  test("Compute hostAddress for legacy MarathonTask instances") {
    val f = new Fixture
    MarathonTasks.hostAddress(f.task1) should equal ("agent1.mesos")
  }

  test("Compute hostAddress for MarathonTask instances with one NetworkInfo") {
    val f = new Fixture
    MarathonTasks.hostAddress(f.task2) should equal ("123.123.123.123")
  }

  test("Compute hostAddress for MarathonTask instances with multiple NetworkInfos") {
    val f = new Fixture
    MarathonTasks.hostAddress(f.task3) should equal ("123.123.123.123")
    MarathonTasks.hostAddress(f.task4) should equal ("agent1.mesos")
  }

}

