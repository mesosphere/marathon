package mesosphere.marathon.tasks

import org.apache.mesos.Protos.Attribute
import mesosphere.marathon.Protos.MarathonTask
import scala.collection.JavaConverters._

object MarathonTasks {
  def makeTask(id: String, host: String, port: Int, attributes: List[Attribute]) = {
    MarathonTask.newBuilder().setId(id).setHost(host).setPort(port).addAllAttributes(attributes.asJava).build()
  }
}
