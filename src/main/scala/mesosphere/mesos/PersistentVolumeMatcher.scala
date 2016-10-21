package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.stream._
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.immutable.Seq

object PersistentVolumeMatcher {
  def matchVolumes(
    offer: Mesos.Offer,
    waitingTasks: Seq[Task.Reserved]): Option[VolumeMatch] = {

    // find all offered persistent volumes
    val availableVolumes: Map[String, Mesos.Resource] = offer.getResourcesList.collect {
      case resource: Mesos.Resource if resource.hasDisk && resource.getDisk.hasPersistence =>
        resource.getDisk.getPersistence.getId -> resource
    }(collection.breakOut)

    def resourcesForTask(task: Task.Reserved): Option[Seq[Mesos.Resource]] = {
      if (task.reservation.volumeIds.map(_.idString).forall(availableVolumes.contains))
        Some(task.reservation.volumeIds.flatMap(id => availableVolumes.get(id.idString)))
      else
        None
    }

    waitingTasks.toStream
      .flatMap { task => resourcesForTask(task).flatMap(rs => Some(VolumeMatch(task, rs))) }
      .headOption
  }

  case class VolumeMatch(task: Task.Reserved, persistentVolumeResources: Seq[Mesos.Resource])
}
