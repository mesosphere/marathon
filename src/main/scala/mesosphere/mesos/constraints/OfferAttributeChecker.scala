package mesosphere.mesos.constraints

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.mesos.Placed
import org.apache.mesos.Protos.{ Attribute, Offer }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

/**
  *  This trait define the contract for any attribute checker.
  */
trait OfferAttributeChecker {

  protected[this] val log = LoggerFactory.getLogger(getClass.getName)
  final protected[this] val GroupByDefault = 0
  val offer: Offer
  val constraint: Constraint
  val allPlaced: Seq[Placed]
  final val cFieldName: String = constraint.getField
  final val cFieldValue: String = constraint.getValue

  import mesosphere.marathon.stream.Implicits._

  final lazy val attributes: Seq[Attribute] =
    offer.getAttributesList.filter(_.getName == cFieldName).to[Seq]

  // Since the validation of any attribute is directly related
  // to the operator, conditions for all operators should be defined.

  final type OperatorCondition = PartialFunction[Operator, Boolean]
  /* Operator Conditions */
  val likeCondition: OperatorCondition
  val unlikeCondition: OperatorCondition
  val uniqueCondition: OperatorCondition
  val groupByCondition: OperatorCondition
  val maxPerCondition: OperatorCondition
  val clusterCondition: OperatorCondition

  /**
    *  Check iterates over the conditions
    *  and decide if the attributes satisfy
    *  the operator.
    */
  lazy val check: Boolean = {
    Seq(constraint.getOperator).map {
      uniqueCondition orElse
        clusterCondition orElse
        groupByCondition orElse
        maxPerCondition orElse
        likeCondition orElse
        unlikeCondition
    }.headOption.getOrElse {
      log.warn(s"Constraint: {$constraint} could not be matched to defined conditions.")
      false
    }
  }
}

final case class HostnameCondition(
    offer: Offer,
    constraint: Constraint,
    allPlaced: Seq[Placed]) extends OfferAttributeChecker {

  override lazy val likeCondition: OperatorCondition = {
    case Operator.LIKE =>
      offer.getHostname.matches(cFieldValue)
  }

  override lazy val unlikeCondition: OperatorCondition = {
    case Operator.UNLIKE =>
      !offer.getHostname.matches(cFieldValue)
  }

  override lazy val uniqueCondition: OperatorCondition = {
    case Operator.UNIQUE =>
      allPlaced.forall(_.hostname != offer.getHostname)
  }

  override lazy val groupByCondition: OperatorCondition = {
    case Operator.GROUP_BY =>
      checkGroupBy(
        offer.getHostname,
        (p: Placed) => Some(p.hostname),
        GroupByDefault,
        cFieldValue,
        allPlaced
      )
  }

  override lazy val maxPerCondition: OperatorCondition = {
    case Operator.MAX_PER =>
      checkMaxPer(offer.getHostname, getIntValue(cFieldValue, 0),
        (p: Placed) => Some(p.hostname), allPlaced)
  }

  override lazy val clusterCondition: OperatorCondition = {
    case Operator.CLUSTER =>
      // Hostname must match or be empty
      (cFieldValue.isEmpty || cFieldValue == offer.getHostname) &&
        // All running tasks must have the same hostname as the one in the offer
        allPlaced.forall(_.hostname == offer.getHostname)
  }
}

final case class AttributeCondition(
    offer: Offer,
    constraint: Constraint,
    allPlaced: Seq[Placed]) extends OfferAttributeChecker {

  private lazy val groupFunc = (p: Placed) => p.attributes
    .find(_.getName == cFieldName)
    .map(getAttributeStringValue)

  override val uniqueCondition: OperatorCondition = {
    case Operator.UNIQUE =>
      matches(allPlaced, cFieldName, attributes).isEmpty
  }

  override val clusterCondition: OperatorCondition = {
    case Operator.CLUSTER =>
      // If no value is set, accept the first one. Otherwise check for it.
      (cFieldValue.isEmpty || attributes.forall(getAttributeStringValue(_) == cFieldValue)) &&
        // All running tasks should have the matching attribute
        matches(allPlaced, cFieldName, attributes).size == allPlaced.size
  }

  override val groupByCondition: OperatorCondition = {
    case Operator.GROUP_BY =>
      attributes.forall { attr =>
        checkGroupBy(getAttributeStringValue(attr), groupFunc, GroupByDefault, cFieldValue, allPlaced)
      }
  }

  override val maxPerCondition: OperatorCondition = {
    case Operator.MAX_PER =>
      checkMaxPer(offer.getHostname, getIntValue(cFieldValue, 0), groupFunc, allPlaced)
  }

  override val likeCondition: OperatorCondition = {
    case Operator.LIKE => checkLike(cFieldValue, attributes)
  }

  override val unlikeCondition: OperatorCondition = {
    case Operator.UNLIKE => checkUnlike(cFieldValue, attributes)
  }
}

