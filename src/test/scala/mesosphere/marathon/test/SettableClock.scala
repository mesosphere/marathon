package mesosphere.marathon
package test

import java.time.{Duration => JavaDuration, _}

import scala.compat.java8.DurationConverters
import scala.concurrent.duration.FiniteDuration

object SettableClock {
  private val defaultJavaClock =
    Clock.fixed(LocalDateTime.of(2015, 4, 9, 12, 30, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

  def ofNow() = new SettableClock(Clock.fixed(Instant.now(), ZoneOffset.UTC))
}

class SettableClock(private[this] var clock: Clock = SettableClock.defaultJavaClock) extends Clock {
  private[this] var subscribers: List[() => Unit] = Nil
  def onChange(fn: () => Unit): Unit =
    synchronized {
      subscribers = fn :: subscribers
    }

  override def getZone: ZoneId = clock.getZone

  override def instant(): Instant = clock.instant()

  override def withZone(zoneId: ZoneId): Clock = new SettableClock(clock.withZone(zoneId))

  def advanceBy(duration: FiniteDuration): this.type =
    advanceBy(DurationConverters.toJava(duration))

  def advanceBy(duration: JavaDuration): this.type = {
    clock = Clock.offset(clock, duration)
    subscribers.foreach(_())
    this
  }

  def advanceTo(instant: Instant): this.type = {
    clock = Clock.fixed(instant, clock.getZone)
    subscribers.foreach(_())
    this
  }
}
