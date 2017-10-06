package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.OfferUtil
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
  def region: Option[String]
  def zone: Option[String]
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
      field match {
        case "hostname" | "*hostname" =>
          checkConstraint(Some(offer.getHostname), { p => Some(p.hostname) })
        case "*region" =>
          checkConstraint(OfferUtil.region(offer), { p => p.region })
        case "*zone" =>
          checkConstraint(OfferUtil.zone(offer), { p => p.zone })
        case _ =>
          checkConstraint(attr.map(getValueString), { p => p.attributes.find(_.getName == field).map(getValueString) })
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

    private def checkCluster(offerValue: String, placedValue: Placed => Option[String]) = {
      // Hostname must match or be empty
      (value.isEmpty || value == offerValue) &&
        // All running tasks must have the same hostname as the one in the offer
        allPlaced.forall { p => placedValue(p) contains offerValue }
    }

    // All running tasks must have a value that is different from the one in the offer
    private def checkUnique(offerValue: Option[String], placedValue: Placed => Option[String]) = {
      allPlaced.forall { p => placedValue(p) != offerValue }
    }

    def checkConstraint(maybeOfferValue: Option[String], placedValue: Placed => Option[String]) = {
      maybeOfferValue match {
        case Some(offerValue) =>
          constraint.getOperator match {
            case Operator.LIKE => checkLike(offerValue)
            case Operator.UNLIKE => checkUnlike(offerValue)
            case Operator.UNIQUE => checkUnique(maybeOfferValue, placedValue)
            case Operator.GROUP_BY => checkGroupBy(offerValue, placedValue)
            case Operator.MAX_PER => checkMaxPer(offerValue, value.toInt, placedValue)
            case Operator.CLUSTER => checkCluster(offerValue, placedValue)
            case _ => false
          }
        case None =>
          // Only unlike can be matched if this offer does not have the specified value
          constraint.getOperator == Operator.UNLIKE
      }
    }

    private def checkLike(offerValue: String): Boolean =
      if (value.nonEmpty) {
        offerValue.matches(value)
      } else {
        log.warn("Error, value is required for LIKE operation")
        false
      }

    private def checkUnlike(offerValue: String): Boolean =
      if (value.nonEmpty) {
        !offerValue.matches(value)
      } else {
        log.warn("Error, value is required for UNLIKE operation")
        false
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
