package mesosphere.marathon
package core.launcher.impl

import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => MesosProtos }

/**
  * Encapsulates information about a reserved resource and its (probably empty) list of reservation labels.
  */
case class ReservationLabels(labels: Map[String, String]) {
  lazy val mesosLabels: MesosProtos.Labels = labels.toMesosLabels

  def get(key: String): Option[String] = labels.get(key)

  override def toString: String = labels.map { case (k, v) => s"$k: $v" }.mkString(", ")
}

object ReservationLabels {
  def withoutLabels: ReservationLabels = new ReservationLabels(Map.empty)

  def apply(resource: MesosProtos.Resource): ReservationLabels = {
    if (resource.hasReservation && resource.getReservation.hasLabels)
      ReservationLabels(resource.getReservation.getLabels)
    else
      ReservationLabels.withoutLabels
  }
  def apply(labels: MesosProtos.Labels): ReservationLabels = {
    ReservationLabels(labels.fromProto)
  }
}
