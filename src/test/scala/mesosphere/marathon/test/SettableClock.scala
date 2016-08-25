package mesosphere.marathon.test

import java.time._

import scala.concurrent.duration.FiniteDuration

class SettableClock(private[this] var clock: Clock = Clock.fixed(Instant.now, ZoneOffset.UTC)) extends Clock {

  override def getZone: ZoneId = clock.getZone

  override def instant(): Instant = clock.instant()

  override def withZone(zoneId: ZoneId): Clock = new SettableClock(clock.withZone(zoneId))

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
