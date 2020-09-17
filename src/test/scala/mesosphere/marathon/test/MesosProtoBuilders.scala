package mesosphere.marathon
package test

import org.apache.mesos.Protos._
import scala.jdk.CollectionConverters._

object MesosProtoBuilders {
  def newAgentId(agentId: String): SlaveID =
    SlaveID.newBuilder().setValue(agentId).build

  def newVolume(mode: Volume.Mode, containerPath: String, source: Volume.Source) = {
    Volume
      .newBuilder()
      .setMode(mode)
      .setContainerPath(containerPath)
      .setSource(source)
      .build()
  }

  object newVolumeSource {
    def docker(driver: String = "", name: String = "", options: Map[String, String] = Map.empty): Volume.Source = {

      val b = Volume.Source.newBuilder
        .setType(Volume.Source.Type.DOCKER_VOLUME)

      val dv = Volume.Source.DockerVolume.newBuilder

      if (driver.nonEmpty)
        dv.setDriver(driver)
      if (name.nonEmpty)
        dv.setName(name)

      if (options.nonEmpty)
        dv.setDriverOptions(Parameters.newBuilder.addAllParameter(options.map {
          case (k, v) => Parameter.newBuilder.setKey(k).setValue(v).build
        }.asJava))

      b.setDockerVolume(dv)
      b.build()
    }
  }
//  def newVolume()
}
