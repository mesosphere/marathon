package mesosphere.marathon.tasks

import org.apache.mesos.Protos.Attribute
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.JavaConverters._
import org.apache.mesos.Protos
import mesosphere.marathon.state.Timestamp

object MarathonTasks {
  def makeTask(id: String,
               host: String,
               ports: Iterable[Long],
               attributes: Iterable[Attribute],
               version: Timestamp): MarathonTask = {
    MarathonTask.newBuilder()
      .setId(id)
      .setHost(host)
      .setVersion(version.toString())
      .addAllPorts(ports.map(i => i.toInt: java.lang.Integer).asJava)
      .addAllAttributes(attributes.asJava)
      .setStagedAt(System.currentTimeMillis)
      .build
  }
}
