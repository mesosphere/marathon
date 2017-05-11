package mesosphere.mesos

import org.apache.mesos.Protos.{ Attribute, Value }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.util.Try

// Projection trait to extract attributes and hostname from
// Instance.
trait Placed {
  def attributes: Seq[Attribute]
  def hostname: String
}

package object constraints {

  /*
      This package object contain helper methods related to constraint
      parsing.
  */

  protected[this] val log = LoggerFactory.getLogger(getClass.getName)

  object Int {
    def unapply(s: String): Option[Int] = Try(s.toInt).toOption
  }

  import mesosphere.marathon.stream.Implicits._

  def getIntValue(s: String, default: Int): Int = s match {
    case "inf" => Integer.MAX_VALUE
    case Int(x) => x
    case _ =>
      log.warn(s"Could not parse number from {$s}, default value {$default} is set instead.")
      default
  }

  def getAttributeStringValue(attribute: Attribute): String = attribute.getType match {
    case Value.Type.SCALAR =>
      java.text.NumberFormat.getInstance.format(attribute.getScalar.getValue)
    case Value.Type.TEXT =>
      attribute.getText.getValue
    case Value.Type.RANGES =>
      val s = attribute.getRanges.getRangeList.to[Seq]
        .sortWith(_.getBegin < _.getBegin)
        .map(r => s"${r.getBegin.toString}-${r.getEnd.toString}")
        .mkString(",")
      s"[$s]"
    case Value.Type.SET =>
      val s = attribute.getSet.getItemList.to[Seq].sorted.mkString(",")
      s"{$s}"
  }

  /**
    * Filters running tasks by matching their attributes to this field & value.
    */
  def matchTaskAttributes(
    allPlaced: Seq[Placed],
    fieldName: String, fieldValue: String): Seq[Placed] = {
    allPlaced.filter {
      _.attributes
        .exists { y =>
          y.getName == fieldName &&
            getAttributeStringValue(y) == fieldValue
        }
    }
  }

  def matches(allPlaced: Seq[Placed], fieldName: String, attributes: Seq[Attribute]): Seq[Placed] = {
    attributes.flatMap { attr =>
      matchTaskAttributes(allPlaced, fieldName, getAttributeStringValue(attr))
    }
  }

  def checkGroupBy(
    constraintValue: String,
    groupFunc: (Placed) => Option[String],
    groupByDefaultValue: Int = 0,
    fieldValue: String,
    allPlaced: Seq[Placed]
  ) = {
    // Minimum group count
    val minimum = Iterable(groupByDefaultValue, getIntValue(fieldValue, groupByDefaultValue)).max
    // Group tasks by the constraint value, and calculate the task count of each group
    val groupedTasks = allPlaced.groupBy(groupFunc).map { case (k, v) => k -> v.size }
    // Task count of the smallest group
    val minCount = groupedTasks.values.reduceOption(_ min _).getOrElse(0)
    // Check if offers are >= minimum groupings
    val taskSizeSatisfied = groupedTasks.size >= minimum
    // Return true if any of these are also true:
    // a) this offer matches the smallest grouping when there
    // are >= minimum groupings
    // b) the constraint value from the offer is not yet in the grouping
    groupedTasks.find {
      case (constraint: Option[String], _) =>
        constraint.contains(constraintValue)
    }.forall {
      case (_, taskCount: Int) =>
        taskSizeSatisfied && taskCount == minCount
    }
  }

  def checkLike(fieldValue: String, attributes: Seq[Attribute]): Boolean = {
    if (fieldValue.nonEmpty) {
      attributes.forall { attr =>
        getAttributeStringValue(attr).matches(fieldValue)
      }
    } else {
      log.warn("Error, value is required for LIKE operation")
      false
    }
  }

  def checkUnlike(fieldValue: String, attributes: Seq[Attribute]): Boolean = {
    if (fieldValue.nonEmpty) {
      !attributes.forall { attr =>
        getAttributeStringValue(attr).matches(fieldValue)
      }
    } else {
      log.warn("Error, value is required for UNLIKE operation")
      false
    }
  }

  def checkMaxPer(constraintValue: String, maxCount: Int,
    groupFunc: (Placed) => Option[String],
    allPlaced: Seq[Placed]): Boolean = {
    // Group tasks by the constraint value, and calculate the task count of each group
    val groupedTasks = allPlaced.groupBy(groupFunc).map { case (k, v) => k -> v.size }

    groupedTasks.find {
      case (constraint: Option[String], _) =>
        constraint.contains(constraintValue)
    }.forall {
      case (_, taskCount: Int) =>
        taskCount < maxCount
    }
  }
}
