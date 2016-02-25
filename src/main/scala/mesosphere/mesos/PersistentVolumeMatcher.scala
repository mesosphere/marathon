package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.JavaConverters._

object PersistentVolumeMatcher {
  def matchVolumes(
    offer: Mesos.Offer,
    app: AppDefinition,
    waitingTasks: Iterable[Task]): Option[VolumeMatch] = {

    // find all offered persistent volumes
    val availableVolumes: Map[String, Mesos.Resource] = offer.getResourcesList.asScala.collect {
      case resource: Mesos.Resource if resource.hasDisk && resource.getDisk.hasPersistence =>
        resource.getDisk.getPersistence.getId -> resource
    }.toMap

    def resourcesForTask(task: Task): Option[Iterable[Mesos.Resource]] = {
      task.reservationWithVolumes match {
        // case 1: there are no required volumes => return a match
        case None => Some(Nil)

        // case 2: all required volumeIds are offered => return a match
        case Some(reservation) if reservation.volumeIds.map(_.idString).forall(availableVolumes.contains) =>
          Some(reservation.volumeIds.flatMap(id => availableVolumes.get(id.idString)))

        // case 3: not all required volumes are offered => no match
        case _ => None
      }
    }

    val matches = waitingTasks.iterator.map { task =>
      resourcesForTask(task).flatMap(rs => Some(VolumeMatch(task, rs)))
    }

    if (matches.hasNext) {
      matches.next()
    }
    else None
  }

  case class VolumeMatch(task: Task, persistentVolumeResources: Iterable[Mesos.Resource])

}
