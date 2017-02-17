package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

object Constraints {

  import constraints._

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  /**
    *
    * Decide if the given Constraint matches the attributes provided in Offer.
    *
    * @param allPlaced projection of Instace object. Caring Attributed and Hostnames.
    * @param offer the offer made for the given host, containing the attributes and the operator.
    * @param constraint given Constraint.
    * @return a decision either the constraint is satisfied or not.
    *         https://mesosphere.github.io/marathon/docs/constraints.html
    */

  def meetsConstraint(allPlaced: Seq[Placed], offer: Offer, constraint: Constraint): Boolean = {
    lazy val attributes = offer.getAttributesList.filter(_.getName == constraint.getField)
    (constraint.getField, attributes) match {
      case ("hostname", _) =>
        HostnameCondition(offer, constraint, allPlaced).check
      case (_, _ :: _) =>
        AttributeCondition(offer, constraint, allPlaced).check
      case _ => constraint.getOperator == Operator.UNLIKE
    }
  }

  /**
    * Select instances to kill while maintaining the constraints of the application definition.
    * Note: It is possible, that the result of this operation does not select as many instances as needed.
    *
    * @param runSpec          the RunSpec.
    * @param runningInstances the list of running instances to filter
    * @param toKillCount      the expected number of instances to select for kill
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
        case field: String => instance.agentInfo.attributes.find(_.getName == field).map(getAttributeStringValue)
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
        val attrs = instance.agentInfo.attributes.map(a => s"${a.getName}=${getAttributeStringValue(a)}").mkString(", ")
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
