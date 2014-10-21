package mesosphere.mesos

import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.{ MarathonTask, Constraint }
import mesosphere.marathon.Protos.Constraint.Operator
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Offer
import scala.util.Try

object Int {
  def unapply(s: String): Option[Int] = Try(s.toInt).toOption
}

object Constraints {

  private[this] val log = Logger.getLogger(getClass.getName)
  val GroupByDefault = 0

  private def getIntValue(s: String, default: Int): Int = s match {
    case "inf"  => Integer.MAX_VALUE
    case Int(x) => x
    case _      => default
  }

  private final class ConstraintsChecker(tasks: Iterable[MarathonTask], offer: Offer, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    lazy val attr = offer.getAttributesList.asScala.find(_.getName == field)

    def isMatch: Boolean =
      if (field == "hostname") {
        checkHostName
      }
      else if (attr.nonEmpty) {
        checkAttribute
      }
      else {
        // This will be reached in case we want to schedule for an attribute
        // that's not supplied.
        false
      }

    private def checkGroupBy(constraintValue: String, groupFunc: (MarathonTask) => Option[String]) = {
      // Minimum group count
      val minimum = List(GroupByDefault, getIntValue(value, GroupByDefault)).max
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = tasks.groupBy(groupFunc).mapValues(_.size)
      // Task count of the smallest group
      val minCount = groupedTasks.values.reduceOption(_ min _).getOrElse(0)

      // Return true if any of these are also true:
      // a) this offer matches the smallest grouping when there
      // are >= minimum groupings
      // b) the constraint value from the offer is not yet in the grouping
      groupedTasks.find(_._1.contains(constraintValue)) match {
        case Some(pair) => (groupedTasks.size >= minimum) && (pair._2 == minCount)
        case None       => true
      }
    }

    private def checkHostName =
      constraint.getOperator match {
        case Operator.LIKE     => offer.getHostname.matches(value)
        case Operator.UNLIKE   => !offer.getHostname.matches(value)
        // All running tasks must have a hostname that is different from the one in the offer
        case Operator.UNIQUE   => tasks.forall(_.getHost != offer.getHostname)
        case Operator.GROUP_BY => checkGroupBy(offer.getHostname, (task: MarathonTask) => Option(task.getHost))
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value == offer.getHostname) &&
            // All running tasks must have the same hostname as the one in the offer
            tasks.forall(_.getHost == offer.getHostname)
        case _ => false
      }

    private def checkAttribute = {
      def matches: Iterable[MarathonTask] = matchTaskAttributes(tasks, field, attr.get.getText.getValue)
      constraint.getOperator match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || attr.get.getText.getValue == value) &&
            // All running tasks should have the matching attribute
            matches.size == tasks.size
        case Operator.GROUP_BY => {
          val groupFunc = (task: MarathonTask) =>
            task.getAttributesList.asScala
              .find(_.getName == field)
              .map(_.getText.getValue)
          checkGroupBy(attr.get.getText.getValue, groupFunc)
        }
        case Operator.LIKE =>
          if (value.nonEmpty) {
            attr.get.getText.getValue.matches(value)
          }
          else {
            log.warn("Error, value is required for LIKE operation")
            false
          }
        case Operator.UNLIKE =>
          if (value.nonEmpty) {
            !attr.get.getText.getValue.matches(value)
          }
          else {
            log.warn("Error, value is required for UNLIKE operation")
            false
          }
      }
    }

    /**
      * Filters running tasks by matching their attributes to this field & value.
      */
    private def matchTaskAttributes(tasks: Iterable[MarathonTask], field: String, value: String) =
      tasks.filter {
        _.getAttributesList.asScala
          .filter { y =>
            y.getName == field &&
              y.getText.getValue == value
          }.nonEmpty
      }
  }

  def meetsConstraint(tasks: Iterable[MarathonTask], offer: Offer, constraint: Constraint): Boolean =
    new ConstraintsChecker(tasks, offer, constraint).isMatch
}
