package mesosphere.marathon.tasks

import org.apache.mesos.Protos.Attribute
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.JavaConverters._
import org.apache.mesos.Protos

object MarathonTasks {
  def makeTask(id: String,
               host: String,
               ports: Iterable[Int],
               attributes: List[Attribute]) = {
    MarathonTask.newBuilder()
      .setId(id)
      .setHost(host)
      .addAllPorts(ports.map(i => i: java.lang.Integer).asJava)
      .addAllAttributes(attributes.asJava)
      .setStagedAt(System.currentTimeMillis)
      .build
  }
}
