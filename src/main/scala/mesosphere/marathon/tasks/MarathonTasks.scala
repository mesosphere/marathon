package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Attribute

import scala.collection.JavaConverters._

object MarathonTasks {
  def makeTask(id: String,
               host: String,
               ports: Iterable[Long],
               attributes: Iterable[Attribute],
               version: Timestamp,
               slaveId: Protos.SlaveID): MarathonTask = {
    MarathonTask.newBuilder()
      .setId(id)
      .setHost(host)
      .setVersion(version.toString())
      .addAllPorts(ports.map(i => i.toInt: java.lang.Integer).asJava)
      .addAllAttributes(attributes.asJava)
      .setStagedAt(version.toDateTime.getMillis + 1000)
      .setSlaveId(slaveId)
      .build
  }
}
