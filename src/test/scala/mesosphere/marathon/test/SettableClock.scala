package mesosphere.marathon
package test

import java.time._

import scala.concurrent.duration.FiniteDuration

object SettableClock {
  private[test] val defaultJavaClock =
    Clock.fixed(LocalDateTime.of(2015, 4, 9, 12, 30, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

  def ofNow() = new SettableClock(Clock.fixed(Instant.now(), ZoneOffset.UTC))
}

class SettableClock(private[this] var clock: Clock = SettableClock.defaultJavaClock) extends Clock {

  override def getZone: ZoneId = clock.getZone

  override def instant(): Instant = clock.instant()

  override def withZone(zoneId: ZoneId): Clock = new SettableClock(clock.withZone(zoneId))

  def +=(duration: FiniteDuration): Unit = plus(duration)

  def plus(duration: FiniteDuration): this.type = {
    clock = Clock.offset(clock, Duration.ofMillis(duration.toMillis))
    this
  }

  def plus(duration: Duration): this.type = {
    clock = Clock.offset(clock, duration)
    this
  }

  def at(instant: Instant): this.type = {
    clock = Clock.fixed(instant, clock.getZone)
    this
  }
}
