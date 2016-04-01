package mesosphere.marathon.core.launcher.impl

import org.apache.mesos.{ Protos => MesosProtos }

case class ResourceLabels(labels: Map[String, String]) {
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

object ResourceLabels {
  def empty: ResourceLabels = new ResourceLabels(Map.empty)

  def apply(resource: MesosProtos.Resource): ResourceLabels = {
    if (resource.hasReservation && resource.getReservation.hasLabels)
      ResourceLabels(resource.getReservation.getLabels)
    else
      ResourceLabels.empty
  }
  def apply(resource: MesosProtos.Labels): ResourceLabels = {
    import scala.collection.JavaConverters._
    ResourceLabels(resource.getLabelsList.asScala.iterator.map(l => l.getKey -> l.getValue).toMap)
  }
}
