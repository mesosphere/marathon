package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.{ Attribute, Offer, Value }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.util.Try

object Int {
  def unapply(s: String): Option[Int] = Try(s.toInt).toOption
}

trait Placed {
  def attributes: Seq[Attribute]
  def hostname: String
}

object Constraints {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  private val GroupByDefault = 0

  private def getIntValue(s: String, default: Int): Int = s match {
    case "inf" => Integer.MAX_VALUE
    case Int(x) => x
    case _ => default
  }

  private def getValueString(attribute: Attribute): String = attribute.getType match {
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

  private final class ConstraintsChecker(allPlaced: Seq[Placed], offer: Offer, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    lazy val attr = offer.getAttributesList.find(_.getName == field)

    def isMatch: Boolean =
      if (field == "hostname") {
        checkHostName
      } else if (attr.nonEmpty) {
        checkAttribute
      } else {
        // This will be reached in case we want to schedule for an attribute
        // that's not supplied.
        checkMissingAttribute
      }

    private def checkGroupBy(constraintValue: String, groupFunc: (Placed) => Option[String]) = {
      // Minimum group count
      val minimum = List(GroupByDefault, getIntValue(value, GroupByDefault)).max
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = allPlaced.groupBy(groupFunc).map { case (k, v) => k -> v.size }
      // Task count of the smallest group
      val minCount = groupedTasks.values.reduceOption(_ min _).getOrElse(0)

      // Return true if any of these are also true:
      // a) this offer matches the smallest grouping when there
      // are >= minimum groupings
      // b) the constraint value from the offer is not yet in the grouping
      groupedTasks.find(_._1.contains(constraintValue))
        .forall(pair => groupedTasks.size >= minimum && pair._2 == minCount)
    }

    private def checkMaxPer(constraintValue: String, maxCount: Int, groupFunc: (Placed) => Option[String]): Boolean = {
      // Group tasks by the constraint value, and calculate the task count of each group
      val groupedTasks = allPlaced.groupBy(groupFunc).map { case (k, v) => k -> v.size }

      groupedTasks.find(_._1.contains(constraintValue)).forall(_._2 < maxCount)
    }

    private def checkHostName =
      constraint.getOperator match {
        case Operator.LIKE => offer.getHostname.matches(value)
        case Operator.UNLIKE => !offer.getHostname.matches(value)
        // All running tasks must have a hostname that is different from the one in the offer
        case Operator.UNIQUE => allPlaced.forall(_.hostname != offer.getHostname)
        case Operator.GROUP_BY => checkGroupBy(offer.getHostname, (p: Placed) => Some(p.hostname))
        case Operator.MAX_PER => checkMaxPer(offer.getHostname, value.toInt, (p: Placed) => Some(p.hostname))
        case Operator.CLUSTER =>
          // Hostname must match or be empty
          (value.isEmpty || value == offer.getHostname) &&
            // All running tasks must have the same hostname as the one in the offer
            allPlaced.forall(_.hostname == offer.getHostname)
        case _ => false
      }

    @SuppressWarnings(Array("OptionGet"))
    private def checkAttribute: Boolean = {
      def matches: Seq[Placed] = matchTaskAttributes(allPlaced, field, getValueString(attr.get))
      def groupFunc = (p: Placed) => p.attributes
        .find(_.getName == field)
        .map(getValueString)
      constraint.getOperator match {
        case Operator.UNIQUE => matches.isEmpty
        case Operator.CLUSTER =>
          // If no value is set, accept the first one. Otherwise check for it.
          (value.isEmpty || getValueString(attr.get) == value) &&
            // All running tasks should have the matching attribute
            matches.size == allPlaced.size
        case Operator.GROUP_BY =>
          checkGroupBy(getValueString(attr.get), groupFunc)
        case Operator.MAX_PER =>
          checkMaxPer(getValueString(attr.get), value.toInt, groupFunc)
        case Operator.LIKE => checkLike
        case Operator.UNLIKE => checkUnlike
      }
    }

    @SuppressWarnings(Array("OptionGet"))
    private def checkLike: Boolean = {
      if (value.nonEmpty) {
        getValueString(attr.get).matches(value)
      } else {
        log.warn("Error, value is required for LIKE operation")
        false
      }
    }

    @SuppressWarnings(Array("OptionGet"))
    private def checkUnlike: Boolean = {
      if (value.nonEmpty) {
        !getValueString(attr.get).matches(value)
      } else {
        log.warn("Error, value is required for UNLIKE operation")
        false
      }
    }

    private def checkMissingAttribute = constraint.getOperator == Operator.UNLIKE

    /**
      * Filters running tasks by matching their attributes to this field & value.
      */
    private def matchTaskAttributes(allPlaced: Seq[Placed], field: String, value: String) =
      allPlaced.filter {
        _.attributes
          .exists { y =>
            y.getName == field &&
              getValueString(y) == value
          }
      }
  }

  def meetsConstraint(allPlaced: Seq[Placed], offer: Offer, constraint: Constraint): Boolean =
    new ConstraintsChecker(allPlaced, offer, constraint).isMatch

  /**
    * Select instances to kill while maintaining the constraints of the application definition.
    * Note: It is possible, that the result of this operation does not select as many instances as needed.
    *
    * @param runSpec the RunSpec.
    * @param runningInstances the list of running instances to filter
    * @param toKillCount the expected number of instances to select for kill
    * @return the selected instances to kill. The number of instances will not exceed toKill but can be less.
    */
  def selectInstancesToKill(
    runSpec: RunSpec, runningInstances: Seq[Instance], toKillCount: Int): Seq[Instance] = {

    require(toKillCount <= runningInstances.size, "Can not kill more instances than running")

    //short circuit, if all instances shall be killed
    if (runningInstances.size == toKillCount) return runningInstances

    //currently, only the GROUP_BY operator is able to select instances to kill
    val distributions = runSpec.constraints.withFilter(_.getOperator == Operator.GROUP_BY).map { constraint =>
      def groupFn(instance: Instance): Option[String] = constraint.getField match {
        case "hostname" => Some(instance.agentInfo.host)
        case field: String => instance.agentInfo.attributes.find(_.getName == field).map(getValueString)
      }
      val instanceGroups: Seq[Map[Instance.Id, Instance]] =
        runningInstances.groupBy(groupFn).values.map(Instance.instancesById)(collection.breakOut)
      GroupByDistribution(constraint, instanceGroups)
    }

    //short circuit, if there are no constraints to align with
    if (distributions.isEmpty) return Seq.empty

    var toKillInstances = Map.empty[Instance.Id, Instance]
    var flag = true
    while (flag && toKillInstances.size != toKillCount) {
      val tried = distributions
        //sort all distributions in descending order based on distribution difference
        .toSeq.sortBy(_.distributionDifference(toKillInstances))(Ordering.Int.reverse)
        //select instances to kill (without already selected ones)
        .flatMap(_.findInstancesToKill(toKillInstances)) ++
        //fallback: if the distributions did not select a instance, choose one of the not chosen ones
        runningInstances.filterNot(instance => toKillInstances.contains(instance.instanceId))

      val matchingInstance = tried.find { tryInstance =>
        distributions.forall(_.isMoreEvenWithout(toKillInstances + (tryInstance.instanceId -> tryInstance)))
      }

      matchingInstance match {
        case Some(instance) => toKillInstances += instance.instanceId -> instance
        case None => flag = false
      }
    }

    //log the selected instances and why they were selected
    if (log.isInfoEnabled) {
      val instanceDesc = toKillInstances.values.map { instance =>
        val attrs = instance.agentInfo.attributes.map(a => s"${a.getName}=${getValueString(a)}").mkString(", ")
        s"${instance.instanceId} host:${instance.agentInfo.host} attrs:$attrs"
      }.mkString("Selected Tasks to kill:\n", "\n", "\n")
      val distDesc = distributions.map { d =>
        val (before, after) = (d.distributionDifference(), d.distributionDifference(toKillInstances))
        s"${d.constraint.getField} changed from: $before to $after"
      }.mkString("Selected Constraint diff changed:\n", "\n", "\n")
      log.info(s"$instanceDesc$distDesc")
    }

    toKillInstances.values.to[Seq]
  }

  /**
    * Helper class for easier distribution computation.
    */
  private case class GroupByDistribution(constraint: Constraint, distribution: Seq[Map[Instance.Id, Instance]]) {
    def isMoreEvenWithout(selected: Map[Instance.Id, Instance]): Boolean = {
      val diffAfterKill = distributionDifference(selected)
      //diff after kill is 0=perfect, 1=tolerated or minimizes the difference
      diffAfterKill <= 1 || distributionDifference() > diffAfterKill
    }

    def findInstancesToKill(without: Map[Instance.Id, Instance]): Seq[Instance] = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size)
      if (updated.size == 1) {
        /* even distributed */
        Seq.empty
      } else {
        updated.maxBy(_._1)._2.flatMap(_.map { case (_, instance) => instance })(collection.breakOut)
      }
    }

    def distributionDifference(without: Map[Instance.Id, Instance] = Map.empty): Int = {
      val updated = distribution.map(_ -- without.keys).groupBy(_.size).keySet
      updated.max - updated.min
    }
  }
}
