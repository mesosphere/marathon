package mesosphere.marathon.core.launcher.impl

import org.apache.mesos.{ Protos => MesosProtos }

/**
  * Encapsulates information about a reserced resource and its (probably empty) list of reservation labels.
  */
case class ReservationSelector(labels: Map[String, String]) {
  lazy val mesosLabels: MesosProtos.Labels = {
    val labelsBuilder = MesosProtos.Labels.newBuilder()
    labels.foreach {
      case (k, v) =>
        labelsBuilder.addLabels(MesosProtos.Label.newBuilder().setKey(k).setValue(v))
    }
    labelsBuilder.build()
  }

  def get(key: String): Option[String] = labels.get(key)

  override def toString: String = labels.map { case (k, v) => s"$k: $v" }.mkString(", ")
}

object ReservationSelector {
  def withoutLabels: ReservationSelector = new ReservationSelector(Map.empty)

  def apply(resource: MesosProtos.Resource): ReservationSelector = {
    if (resource.hasReservation && resource.getReservation.hasLabels)
      ReservationSelector(resource.getReservation.getLabels)
    else
      ReservationSelector.withoutLabels
  }
  def apply(labels: MesosProtos.Labels): ReservationSelector = {
    import scala.collection.JavaConverters._
    ReservationSelector(labels.getLabelsList.asScala.iterator.map(l => l.getKey -> l.getValue).toMap)
  }
}
