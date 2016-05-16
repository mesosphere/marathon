package mesosphere.mesos

import javax.inject.Inject

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.PathId
import mesosphere.util.{ RWLock, Bag, CircularBuffer }

import scala.collection.mutable

case class RejectionReason(unmatches: Set[NoMatch], constraints: Set[Constraint])

trait RejectOfferCollector {
  def addRejection(appId: PathId, rejected: RejectionReason): Unit

  def getStatsFor(appId: PathId): Stats
}

object RejectOfferCollector {
  val DefaultWindowSize = 50
}

case class DataAndStats(data: CircularBuffer[RejectionReason], stats: Bag[String], var count: Int)

case class Stats(stats: Bag[String], count: Int)

class InMemRejectOfferCollector(windowSize: Integer = RejectOfferCollector.DefaultWindowSize)
    extends RejectOfferCollector {

  @Inject() def this() {
    this(RejectOfferCollector.DefaultWindowSize)
  }

  val lockedMap = RWLock(mutable.Map[PathId, DataAndStats]())

  def addRejection(appId: PathId, rejectionReason: RejectionReason): Unit = {
    lockedMap.writeLock(map => {
      val actual = map.get(appId)
        .getOrElse(new DataAndStats(new CircularBuffer[RejectionReason](windowSize), new Bag[String](), 0))

      if (actual.data.isFull) {
        val toRemove = actual.data.nextValue
        actual.stats.delete(createResourcesId(toRemove.unmatches, toRemove.constraints))
      }
      else {
        actual.count += 1
      }

      actual.data.add(rejectionReason)
      actual.stats.add(createResourcesId(rejectionReason.unmatches, rejectionReason.constraints))

      map += appId -> actual
    }
    )
  }

  def getStatsFor(appId: PathId): Stats = {
    lockedMap.readLock(map => {
      val stats = map.get(appId)
      stats.map(s => Stats(s.stats.deepClone, s.count)).getOrElse(Stats(Bag.empty, 0))
    })
  }

  private def createResourcesId(unmatched: Set[NoMatch], constraints: Set[Constraint]): String = {
    val allUnmatchedSorted = unmatched.map(u => s"${u.resourceName}(${u.requiredValue.toInt})").toList.sorted ++
      constraints.map(_.toString.replaceAll("\\n", " ")).toList.sorted

    allUnmatchedSorted.mkString("", " + ", "")
  }

  //TODO: rejectOfferCollector store event in ZK
}

