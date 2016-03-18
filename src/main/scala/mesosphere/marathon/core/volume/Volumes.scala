package mesosphere.marathon.core.volume

import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Volume
import mesosphere.marathon.state.PersistentVolume

sealed trait Volumes[T <: Volume] {
  val name: String
  def apply(app: AppDefinition): Iterable[T]
}

object AgentVolumes extends Volumes[PersistentVolume] {

  val name = "agent"

  def isAgentLocal(volume: PersistentVolume): Boolean = {
    !volume.persistent.providerName.isDefined || volume.persistent.providerName == name
  }

  def validateVolume(volume: PersistentVolume): Unit = {
    if (!volume.persistent.size.isDefined) {
      throw new WrongConfigurationException("size must be specified for agent local volumes")
    }
  }

  override def apply(app: AppDefinition): Iterable[PersistentVolume] = {
    val result: Iterable[PersistentVolume] = app.persistentVolumes.filter { volume => isAgentLocal(volume) }
    result.foreach{ validateVolume }
    result
  }

  def local(app: AppDefinition): Iterable[Task.LocalVolume] = {
    apply(app).map{ volume => Task.LocalVolume(Task.LocalVolumeId(app.id, volume), volume) }
  }

  /**
    * @return the disk resources required for volumes
    */
  def diskSize(app: AppDefinition): Double = {
    apply(app).map(
      _.persistent.size match {
        case Some(size) => size
        case _          => 0
      }
    ).sum.toDouble
  }

}
