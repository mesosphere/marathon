package mesosphere.mesos

import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import java.util.logging.Logger
import org.apache.mesos.Protos.Offer

object Int {
  def unapply(s: String): Option[Int] = try {
    Some(s.toInt)
  } catch {
    case e: java.lang.NumberFormatException => None
  }
}

// TODO this needs a refactor
object Constraints {

  private[this] val log = Logger.getLogger(getClass.getName)
  val groupByDefault = 0

  def getIntValue(s: String, default: Int): Int = s match {
    case "inf" => Integer.MAX_VALUE
    case Int(x) => x
    case _ => default
  }

  def meetsConstraint(tasks: Iterable[mesosphere.marathon.Protos.MarathonTask],
                      offer: Offer,
                      constraint: Constraint): Boolean = {

    val field = constraint.getField
    val value = constraint.getValue

    if (field == "hostname") {
      return constraint.getOperator match {
        case Operator.LIKE =>
          offer.getHostname.matches(value)
        case Operator.UNIQUE =>
          // All running tasks must have a hostname that is different from the one in the offer
          tasks.forall(_.getHost != offer.getHostname)
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value == offer.getHostname) &&
          // All running tasks must have the same hostname as the one in the offer
          tasks.forall(_.getHost == offer.getHostname)
      }
    }

    val attr = offer.getAttributesList.asScala.find(_.getName == field)

    if (attr.nonEmpty) {
      val matches = matchTaskAttributes(tasks, field, attr.get.getText.getValue)
      constraint.getOperator match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || attr.get.getText.getValue == value) &&
          // All running tasks should have the matching attribute
          matches.size == tasks.size
        case Operator.GROUP_BY =>
          val minimum = List(groupByDefault, getIntValue(value, groupByDefault)).max
          // Group tasks by the constraint value
          val groupedTasks = tasks.groupBy(
            x =>
              x.getAttributesList.asScala
                .find(y =>
                y.getName == field)
                .map(y =>
                y.getText.getValue)
          )

          // Order groupings by smallest first
          val orderedByCount = groupedTasks.toSeq.sortBy(_._2.size)
          val minValue = orderedByCount.headOption.map(_._1).getOrElse("")

          // Return true if any of these are also true:
          // a) this offer matches the smallest grouping when there
          // are >= minimum groupings
          // b) the constraint value from the offer is not yet in the grouping
          val condA =
            orderedByCount.size >= minimum &&
            // true if the smallest group has this attribute value
            (minValue == attr.get.getText.getValue ||
            // or all groups are the same size
              orderedByCount.headOption.map(_._2.size) == orderedByCount.lastOption.map(_._2.size))

          val condB =
            !orderedByCount.exists(x =>
              x._1.getOrElse("") == attr.get.getText.getValue)

          condA || condB
        case Operator.LIKE => {
          if (value.nonEmpty) {
            attr.get.getText.getValue.matches(value)
          } else {
            log.warning("Error, value is required for LIKE operation")
            false
          }
        }
      }
    } else {
      // This will be reached in case we want to schedule for an attribute
      // that's not supplied.
      false
    }
  }

  /**
   * Filters running tasks by matching their attributes to this field & value.
   * @param tasks
   * @param field
   * @param value
   * @return
   */
  private def matchTaskAttributes(tasks: Iterable[mesosphere.marathon.Protos.MarathonTask],
                         field: String,
                         value: String) = {
    tasks
      .filter(x =>
      (x.getAttributesList.asScala)
        .filter(y => {
          y.getName == field &&
          y.getText.getValue == value})
        .nonEmpty)
  }
}
