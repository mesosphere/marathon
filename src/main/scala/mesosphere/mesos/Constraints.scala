package mesosphere.mesos

import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Main
import java.util.logging.Logger

object Int {
  def unapply(s: String): Option[Int] = try {
    Some(s.toInt)
  } catch {
    case e: java.lang.NumberFormatException => None
  }
}

object Constraints {

  private[this] val log = Logger.getLogger(getClass.getName)
  val groupByDefault = 0

  def getIntValue(s: String, default: Int): Int = s match {
    case "inf" => Integer.MAX_VALUE
    case Int(x) => x
    case _ => default
  }


  def meetsConstraint(tasks: Set[mesosphere.marathon.Protos.MarathonTask],
                      attributes: Set[org.apache.mesos.Protos.Attribute],
                      hostname: String,
                      constraint: Constraint): Boolean = {
    meetsConstraint(tasks, attributes, hostname, constraint.getField, constraint.getOperator,
      Option(constraint.getValue))
  }

  def meetsConstraint(tasks: Set[mesosphere.marathon.Protos.MarathonTask],
                      attributes: Set[org.apache.mesos.Protos.Attribute],
                      hostname: String,
                      field: String,
                      op: Operator,
                      value : Option[String]): Boolean = {

    //TODO(*): Implement LIKE (use value for this)

    if (field == "hostname") {
      return op match {
        case Operator.UNIQUE =>
          // All running tasks must have a hostname that is different from the one in the offer
          tasks.forall(_.getHost != hostname)
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value.get == hostname) &&
          // All running tasks must have the same hostname as the one in the offer
          tasks.forall(_.getHost == hostname)
      }
    }

    val attr = attributes.find(_.getName == field)

    if (attr.nonEmpty) {
      val matches = matchTaskAttributes(tasks, field, attr.get.getText.getValue)
      op match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || attr.get.getText.getValue == value.get) &&
          // All running tasks should have the matching attribute
          matches.size == tasks.size
        case Operator.GROUP_BY =>
          val minimum = List(groupByDefault, getIntValue(value.getOrElse(""),
            groupByDefault)).max
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
            field match {
              case "hostname" => hostname.matches(value.get)
              case _ => attr.get.getText.getValue.matches(value.get)
            }
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

  private def matchLike(attributes: Set[org.apache.mesos.Protos.Attribute],
    hostname: String,
    field: String,
    op: Operator,
    value : String): Boolean = {

    (attributes.filter(_.getName == field).filter(_.getText == value).nonEmpty
      || (field == "hostname" && hostname.matches(value)))
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
