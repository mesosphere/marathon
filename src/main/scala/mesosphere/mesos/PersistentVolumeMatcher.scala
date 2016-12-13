package mesosphere.mesos

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task.Reservation
import mesosphere.marathon.stream._
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.immutable.Seq

object PersistentVolumeMatcher {
  def matchVolumes(
    offer: Mesos.Offer,
    waitingInstances: Seq[Instance]): Option[VolumeMatch] = {

    // find all offered persistent volumes
    val availableVolumes: Map[String, Mesos.Resource] = offer.getResourcesList.collect {
      case resource: Mesos.Resource if resource.hasDisk && resource.getDisk.hasPersistence =>
        resource.getDisk.getPersistence.getId -> resource
    }(collection.breakOut)

    def resourcesForReservation(reservation: Reservation): Option[Seq[Mesos.Resource]] = {
      if (reservation.volumeIds.map(_.idString).forall(availableVolumes.contains))
        Some(reservation.volumeIds.flatMap(id => availableVolumes.get(id.idString)))
      else
        None
    }

    waitingInstances.toStream
      .flatMap { instance =>
        // Note this only supports AppDefinition instances with exactly one task
        instance.tasksMap.values.headOption.flatMap(_.reservationWithVolumes).flatMap { reservation =>
          resourcesForReservation(reservation).flatMap(rs => Some(VolumeMatch(instance, rs)))
        }
      }.headOption
  }

  case class VolumeMatch(instance: Instance, persistentVolumeResources: Seq[Mesos.Resource])
}
