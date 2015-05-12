package mesosphere.mesos

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource, SetResource }
import mesosphere.mesos.protos.{ Range => MesosRange }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Offer
import org.apache.mesos.Protos.{ Resource => ProtoResource }
import scala.collection.immutable.Seq

import scala.collection.JavaConverters._

object ResourceMatcher {
  private[this] val log = Logger.getLogger(getClass)

  case class ResourceMatch(matched: Seq[Resource])

  def getName(r: Resource): String = r match {
    case ScalarResource(name, _, _) => name
    case RangesResource(name, _, _) => name
    case SetResource(name, _, _)    => name
  }
  def getRole(r: Resource): String = r match {
    case ScalarResource(_, _, role) => role
    case RangesResource(_, _, role) => role
    case SetResource(_, _, role)    => role
  }

  def matchResources(offer: Offer, app: AppDefinition, config: MarathonConf,
                     runningTasks: => Set[MarathonTask]): Option[ResourceMatch] = {
    val meetsAllConstraints: Boolean = {
      lazy val tasks = runningTasks
      val badConstraints = app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty) {
        log.warn(
          s"Offer did not satisfy constraints for app [${app.id}].\n" +
            s"Conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    if (meetsAllConstraints)
      matchAll(app.resourcesToSeq, offer, app, config).map { rs => ResourceMatch(rs) }
    else
      None
  }

  private def takeResource(need: Resource, offered: Resource, config: MarathonConf): Option[Resource] = {
    import mesosphere.mesos.protos.Implicits._
    def matchName(a: Resource, b: Resource): Boolean = {
      val protoA: ProtoResource = a
      val protoB: ProtoResource = b
      protoA.getName() == protoB.getName()
    }
    if (!matchName(need, offered))
      None
    else {
      need match {
        case ScalarResource(name, value, _) =>
          offered match {
            case ScalarResource(_, valueOffered, roleOffered) =>
              if (value <= valueOffered)
                Some(ScalarResource(name, value, roleOffered))
              else
                None
            case RangesResource(_, rangesOffered, roleOffered) =>
              if (config.useExtendedResourceMatch) {
                // For example:
                //    resource given:    90.2
                //    resource offered:  ranges of (1-100), (200-300)
                //    actual request:    ranges of (90-91)
                rangesOffered.find { r =>
                  if (r.begin <= value.floor && r.end >= value.ceil)
                    true
                  else
                    false
                } match {
                  case Some(_) =>
                    Some(RangesResource(name, Seq(MesosRange(value.floor.toLong,
                      value.ceil.toLong)), roleOffered))
                  case None => None
                }
              }
              else
                None
            case SetResource(_, itemsOffered, roleOffered) =>
              if (config.useExtendedResourceMatch) {
                // For example:
                //    resource given:    90.2
                //    resource offered:  ranges of (1-100), (200-300)
                //    actual request:    ranges of (90-91)
                val took = itemsOffered.take(value.ceil.toInt)
                if (took.size == value.ceil.toInt)
                  Some(SetResource(name, took, roleOffered))
                else
                  None
              }
              else None
          }

        case RangesResource(name, ranges, _) =>
          offered match {
            case RangesResource(_, rangesOffered, roleOffered) =>
              val satisfied = for (
                s <- ranges;
                if rangesOffered.find(r => (s.begin >= r.begin && s.end <= r.end)).nonEmpty
              ) yield s
              if (ranges.size == satisfied.size)
                Some(RangesResource(name, ranges, roleOffered))
              else
                None
            case _ => None
          }

        case SetResource(name, items, _) =>
          offered match {
            case SetResource(_, itemsOffered, roleOffered) =>
              if (items.subsetOf(itemsOffered))
                Some(SetResource(name, items, roleOffered))
              else
                None
            case _ => None
          }
      }
    }
  }

  private def matchAll(needed: Seq[Resource], offer: Offer,
                       app: AppDefinition, config: MarathonConf): Option[Seq[Resource]] = {
    import mesosphere.mesos.protos.Implicits._

    def doMatch(needed: Seq[Resource], offered: Seq[Resource],
                result: Seq[Resource] = Seq()): Option[Seq[Resource]] = {
      needed match {
        case resNeeded :: tl =>
          var need: Option[Resource] = None
          val remains = offered.filter { o =>
            if (need.isEmpty) {
              if (getName(resNeeded) == Resource.PORTS) {
                new PortsMatcher(app, offer).portRanges match {
                  case None =>
                    log.warn("App ports are not available in the offer.")
                    true

                  case x @ Some(portRanges) =>
                    log.debug("Met all constraints.")
                    need = x
                    false
                }
              }
              else
                takeResource(resNeeded, o, config) match {
                  case None        => true
                  case x @ Some(_) => need = x; false
                }
            }
            else
              true
          }

          need match {
            case Some(x) => doMatch(tl, remains, result ++ Seq(x))
            case _       => None
          }
        case Nil if result.nonEmpty => Some(result)
        case _                      => None
      }
    }

    val offers = for (r <- offer.getResourcesList().asScala.toList)
      yield implicitly[Resource](r)

    doMatch(needed, offers)
  }

}

