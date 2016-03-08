package mesosphere.mesos

import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos

/** The result of an attempted scalar resource match. */
sealed trait ScalarMatchResult {
  /** The name of the matched resource. */
  def resourceName: String
  /** The total scalar value to match. */
  def requiredValue: Double
  /** Did the offer contain the required resources? */
  def matches: Boolean
}

object ScalarMatchResult {
  /**
    * Express the scope of the match result. This is only interesting for disk resources
    * to distinguish between matching including volume resources and without them.
    */
  sealed trait Scope {
    def note: String = ""
  }
  object Scope {
    /** Normal match scope for non-disk resources */
    case object NoneDisk extends Scope
    case object IncludingLocalVolumes extends Scope {
      override def note: String = " including volumes"
    }
    case object ExcludingLocalVolumes extends Scope {
      override def note: String = " excluding volumes"
    }
  }
}

/** An unsuccessful match of a scalar resource. */
case class NoMatch(resourceName: String, requiredValue: Double, offeredValue: Double, scope: ScalarMatchResult.Scope)
    extends ScalarMatchResult {

  require(scope == ScalarMatchResult.Scope.NoneDisk || resourceName == Resource.DISK)
  require(requiredValue > offeredValue)

  def matches: Boolean = false
  override def toString: String = {
    s"$resourceName${scope.note} NOT SATISFIED ($requiredValue > $offeredValue)"
  }
}

/** A successful match of a scalar resource requirement. */
case class ScalarMatch(
    resourceName: String, requiredValue: Double,
    consumed: Iterable[ScalarMatch.Consumption], scope: ScalarMatchResult.Scope) extends ScalarMatchResult {

  require(scope == ScalarMatchResult.Scope.NoneDisk || resourceName == Resource.DISK)
  require(consumedValue >= requiredValue)

  def matches: Boolean = true
  def consumedResources: Iterable[Protos.Resource] = {
    consumed.map {
      case ScalarMatch.Consumption(value, role, reservation) =>
        import mesosphere.mesos.protos.Implicits._
        val builder = ScalarResource(resourceName, value, role).toBuilder
        reservation.foreach(builder.setReservation(_))
        builder.build()
    }
  }

  def roles: Iterable[String] = consumed.map(_.role)

  lazy val consumedValue: Double = consumed.iterator.map(_.consumedValue).sum

  override def toString: String = {
    s"$resourceName${scope.note} SATISFIED ($requiredValue <= $consumedValue)"
  }
}

object ScalarMatch {
  /** A (potentially partial) consumption of a scalar resource. */
  case class Consumption(consumedValue: Double, role: String, reservation: Option[Protos.Resource.ReservationInfo])
}
