package mesosphere.mesos

import javax.inject.Inject

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.PathId

import scala.collection.mutable

case class RejectionReason(unmatches: Set[NoMatch], constraints: Set[Constraint])

trait RejectOfferCollector {
  def addRejection(appId: PathId, rejected: RejectionReason): Unit

  def getStatsFor(appId: PathId): Stats
}

object RejectOfferCollector {
  val DefaultWindowSize = 50
}

class Bag[T] {
  val map = mutable.Map[T, Integer]().withDefaultValue(0)

  def add(key: T, count: Integer = 1): Unit = {
    map += key -> (count + map(key))
  }

  def count(key: T): Integer = {
    map.getOrElse(key, 0)
  }

  def delete(key: T, count: Integer = 1): Boolean = {
    val actualVal = map(key)
    if (actualVal > count) {
      map += key -> (actualVal - count)
      true
    }
    else if (actualVal == count) {
      map.remove(key)
      true
    }
    else {
      false
    }
  }

  def allKeys: collection.Set[T] = map.keySet
}

class CircularBufferIterator[T](buffer: Array[T], start: Int) extends Iterator[T] {
  var idx = 0

  override def hasNext: Boolean = idx < buffer.size

  override def next: T = {
    val i = idx
    idx = idx + 1
    buffer(i)
  }
}

class CircularBuffer[T](size: Int, var isFull: Boolean = false)(implicit m: Manifest[T]) extends Seq[T] {
  val buffer = new Array[T](size)
  var bIdx = 0

  override def apply(idx: Int): T = buffer((bIdx + idx) % size)

  override def length: Int = size

  override def iterator: Iterator[T] = new CircularBufferIterator[T](buffer, bIdx)

  def add(e: T): Unit = {
    buffer(bIdx) = e
    if (!isFull && bIdx + 1 == size) isFull = true
    bIdx = (bIdx + 1) % size
  }

  def nextValue: T = {
    buffer(bIdx)
  }
}

case class DataAndStats(data: CircularBuffer[RejectionReason], stats: Bag[String], var count: Int)

case class Stats(stats: Bag[String], count: Int)

class InMemRejectOfferCollector(windowSize: Integer = RejectOfferCollector.DefaultWindowSize)
    extends RejectOfferCollector {

  @Inject() def this() {
    this(RejectOfferCollector.DefaultWindowSize)
  }

  val map = mutable.Map[PathId, DataAndStats]().withDefaultValue(DataAndStats(
    new CircularBuffer[RejectionReason](windowSize), new Bag[String](), 0))

  def addRejection(appId: PathId, rejectionReason: RejectionReason): Unit = {
    val actual = map(appId)

    if (actual.data.isFull) {
      val toRemove = actual.data.nextValue
      toRemove.unmatches.foreach(e => actual.stats.delete(e.resourceName))
      toRemove.constraints.foreach(e => actual.stats.delete(e.toString))
    }
    else {
      actual.count += 1
    }

    actual.data.add(rejectionReason)
    rejectionReason.unmatches.foreach(e => actual.stats.add(e.resourceName))
    rejectionReason.constraints.foreach(e => actual.stats.add(e.toString))

    map += appId -> actual
  }

  def getStatsFor(appId: PathId): Stats = {
    val stats = map(appId)
    Stats(stats.stats, stats.count)
  }
}

//TODO: make rejectOfferCollector with storage
