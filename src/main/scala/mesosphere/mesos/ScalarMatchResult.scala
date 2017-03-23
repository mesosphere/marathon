package mesosphere.mesos

import mesosphere.marathon.raml._
import mesosphere.marathon.state.{ DiskType, PersistentVolume, DiskSource }
import mesosphere.marathon.tasks.ResourceUtil
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Resource.DiskInfo
import org.apache.mesos.Protos.Resource.ReservationInfo

import scala.collection.immutable.Seq

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

  trait Consumption
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

/**
  * Indicates a succesful match.
  */
sealed trait ScalarMatch extends ScalarMatchResult {
  final def matches: Boolean = true
  def consumedResources: Seq[Protos.Resource]
  def roles: Seq[String]
  def consumed: Seq[ScalarMatchResult.Consumption]
}

/** A successful match of a scalar resource requirement. */
case class GeneralScalarMatch(
    resourceName: String, requiredValue: Double,
    consumed: Seq[GeneralScalarMatch.Consumption], scope: ScalarMatchResult.Scope) extends ScalarMatch {

  require(resourceName != Resource.DISK, "DiskResourceMatch is used for disk resources")
  require(consumedValue >= requiredValue)

  def consumedResources: Seq[Protos.Resource] = {
    consumed.map {
      case GeneralScalarMatch.Consumption(value, role, reservation) =>
        import mesosphere.mesos.protos.Implicits._
        val builder = ScalarResource(resourceName, value, role).toBuilder
        reservation.foreach(builder.setReservation)
        builder.build()
    }
  }

  def roles: Seq[String] = consumed.map(_.role)

  lazy val consumedValue: Double = consumed.map(_.consumedValue).sum

  override def toString: String = {
    s"$resourceName${scope.note} SATISFIED ($requiredValue <= $consumedValue)"
  }
}

object GeneralScalarMatch {
  /** A (potentially partial) consumption of a scalar resource. */
  case class Consumption(consumedValue: Double, role: String,
    reservation: Option[ReservationInfo]) extends ScalarMatchResult.Consumption
}

case class DiskResourceMatch(
    diskType: DiskType,
    consumed: Seq[DiskResourceMatch.Consumption],
    scope: ScalarMatchResult.Scope) extends ScalarMatch {

  lazy val consumedValue: Double = consumed.map(_.consumedValue).sum
  def resourceName: String = Resource.DISK
  def requiredValue: Double =
    consumed.foldLeft(0.0)(_ + _.consumedValue)

  def consumedResources: Seq[Protos.Resource] = {
    consumed.map {
      case DiskResourceMatch.Consumption(value, role, reservation, source, _) =>
        import mesosphere.mesos.protos.Implicits._
        val builder = ScalarResource(resourceName, value, role).toBuilder
        reservation.foreach(builder.setReservation(_))
        source.asMesos.foreach { s =>
          builder.setDisk(DiskInfo.newBuilder.setSource(s))
        }
        builder.build()
    }
  }

  def roles: Seq[String] = consumed.map(_.role)

  /**
    * return all volumes for this disk resource match
    * Distinct because a persistentVolume may be associated with multiple resources.
    */
  def volumes: Seq[(DiskSource, PersistentVolume)] =
    consumed.collect {
      case d @ DiskResourceMatch.Consumption(_, _, _, _, Some(volume)) => (d.source, volume)
    }.toList.distinct

  override def toString: String = {
    s"disk${scope.note} for type $diskType SATISFIED"
  }
}

object DiskResourceMatch {
  /** A (potentially partial) consumption of a scalar resource. */
  case class Consumption(consumedValue: Double, role: String,
      reservation: Option[ReservationInfo], source: DiskSource,
      persistentVolume: Option[PersistentVolume]) extends ScalarMatchResult.Consumption {

    def requested: Either[Double, PersistentVolume] =
      persistentVolume.map(Right(_)).getOrElse(Left(consumedValue))
  }
  type ApplyFn = ((Double, String, Option[ReservationInfo], DiskSource, Option[PersistentVolume]) => Consumption)
  object Consumption extends ApplyFn {
    def apply(
      c: GeneralScalarMatch.Consumption,
      source: Option[DiskInfo.Source],
      persistentVolume: Option[PersistentVolume]): Consumption = {
      Consumption(c.consumedValue, c.role, c.reservation, DiskSource.fromMesos(source), persistentVolume)
    }
  }

}

case class DiskResourceNoMatch(
    consumed: Seq[DiskResourceMatch.Consumption],
    resourcesRemaining: Seq[Protos.Resource],
    failedWith: Either[Double, PersistentVolume],
    scope: ScalarMatchResult.Scope) extends ScalarMatchResult {

  import ResourceUtil.RichResource

  def resourceName: String = Resource.DISK
  def requiredValue: Double = {
    failedWith.right.map(_.persistent.size.toDouble).merge + consumed.foldLeft(0.0)(_ + _.consumedValue)
  }

  def requestedStringification(requested: Either[Double, PersistentVolume]): String = requested match {
    case Left(value) => s"disk:root:${value}"
    case Right(vol) =>
      val constraintsJson: Seq[Seq[String]] = vol.persistent.constraints.map(_.toRaml[Seq[String]])(collection.breakOut)
      s"disk:${vol.persistent.`type`.toString}:${vol.persistent.size}:[${constraintsJson.mkString(",")}]"
  }

  def matches: Boolean = false
  override def toString: String = {
    val remainingStr = resourcesRemaining.map(_.getStringification).mkString(";")
    val initialNote = s"disk${scope.note} NOT SATISFIED ... could not satisfy request " +
      requestedStringification(failedWith) + " with offered resources " +
      remainingStr
    // TODO - revisit
    if (consumed.isEmpty) {
      initialNote
    } else {
      val consumedStr = consumed.map { c =>
        s"${c.source} for ${requestedStringification(c.requested)}"
      }.mkString(";")

      initialNote + s" after consuming resources $consumedStr"
    }
  }
}
