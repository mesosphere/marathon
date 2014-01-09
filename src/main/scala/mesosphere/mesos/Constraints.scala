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
    var meetsConstraints = true
    if (tasks.isEmpty) {
      //TODO(*)  This is a bit suboptimal as we're just accepting the first slot
      //         that fulfills, e.g. a cluster constraint. However, for cluster
      //         to ensure placing N instances, we should select the largest offer
      //         first. (This is a optimization).
      true
    } else {

      if (field == "hostname") {
        return op match {
          case Operator.UNIQUE => tasks.filter(
            _.getHost == hostname).isEmpty
          case Operator.CLUSTER => tasks.filter(
            _.getHost == hostname) == tasks.size
        }
      }

      val attr = attributes.filter(_.getName == field).headOption

      meetsConstraints = if (attr.nonEmpty) {
        val matches = matchTaskAttributes(tasks, field, attr.get.getText.getValue)
        op match {
          case Operator.UNIQUE => matches.isEmpty
          case Operator.CLUSTER => matches.size == tasks.size
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

            val maxCount = orderedByCount.last._2.size
            val minValue = orderedByCount.head._1.getOrElse("")

            // Return true if any of these are also true:
            // a) this offer matches the smallest grouping when there
            // are >= minimum groupings
            // b) the constraint value from the offer is not yet in the grouping
            val condA =
              (orderedByCount.size >= minimum &&
                minValue == attr.get.getText.getValue)

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
    meetsConstraints
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
