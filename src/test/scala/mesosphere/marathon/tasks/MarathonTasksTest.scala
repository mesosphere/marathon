package mesosphere.marathon.tasks

import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers
import mesosphere.marathon.Protos._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.Timestamp

import scala.collection.JavaConverters._

class MarathonTasksTest extends MarathonSpec with Matchers {

  class Fixture {
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
      .setSlaveId(mesos.SlaveID.newBuilder
        .setValue("abcd-1234"))
      .setNetwork(NetworkInfos.newBuilder
        .addAllNetworks(Seq(
          mesos.NetworkInfo.newBuilder
            .setIpAddress("123.123.123.123")
            .build).asJava))
      .build

    val task3 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder
        .setValue("abcd-1234"))
      .setNetwork(NetworkInfos.newBuilder
        .addAllNetworks(Seq(
          mesos.NetworkInfo.newBuilder.build,
          mesos.NetworkInfo.newBuilder
            .setIpAddress("123.123.123.123")
            .build).asJava))
      .build

    val task4 = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(mesos.SlaveID.newBuilder
        .setValue("abcd-1234"))
      .setNetwork(NetworkInfos.newBuilder
        .addAllNetworks(Seq(
          mesos.NetworkInfo.newBuilder.build,
          mesos.NetworkInfo.newBuilder.build).asJava))
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

