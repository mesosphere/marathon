package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.JavaConverters._

object PersistentVolumeMatcher {
  // FIXME (217): This is completely un-optimized
  def matchVolumes(
    offer: Mesos.Offer,
    app: AppDefinition,
    waitingTasks: Iterable[Task]): Option[VolumeMatch] = {

    // step 1: find all offered persistent volumes that match this
    val availableVolumes: Map[String, Mesos.Resource] = offer.getResourcesList.asScala.collect {
      case resource: Mesos.Resource if resource.hasDisk && resource.getDisk.hasPersistence =>
        resource.getDisk.getPersistence.getId -> resource
    }.toMap

    def resourcesForTask(task: Task): Option[Iterable[Mesos.Resource]] = {
      task.reservationWithVolumes match {
        // in case there are no required volumes, return a match
        case None => Some(Nil)

        case Some(reservation) =>
          val found = reservation.volumeIds.map { id =>
            availableVolumes.get(id.idString)
          }
          if (found.forall(_.isDefined)) {
            Some(found.flatten)
          }
          else {
            None
          }
      }
    }

    val satisfiedTasks = for {
      task <- waitingTasks
      resources <- resourcesForTask(task)
    } yield (task, resources)

    satisfiedTasks.headOption match {
      case Some((task, resources)) => Some(VolumeMatch(task, resources))
      case _                       => None
    }

  }

  case class VolumeMatch(task: Task, persistentVolumeResources: Iterable[Mesos.Resource])

}
