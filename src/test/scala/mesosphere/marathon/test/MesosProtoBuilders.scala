package mesosphere.marathon
package test

import org.apache.mesos.Protos._

object MesosProtoBuilders {
  def newAgentId(agentId: String): SlaveID =
    SlaveID.newBuilder().setValue(agentId).build
}
