package mesosphere.marathon
package stream

import java.time.{Clock, Duration => JavaDuration}

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.compat.java8.DurationConverters
import scala.concurrent.duration.FiniteDuration

class RateLimiterFlow[U] private (rate: FiniteDuration, clock: Clock) extends GraphStage[FlowShape[U, U]] {
  val input = Inlet[U]("rate-limiter-input")
  val output = Outlet[U]("rate-limiter-output")

  override val shape = FlowShape(input, output)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with StageLogging {

      var nextPullAllowed = clock.instant()

      setHandler(
        input,
        new InHandler {
          override def onPush(): Unit = {
            push(output, grab(input))
          }
        }
      )

      setHandler(
        output,
        new OutHandler {
          override def onPull(): Unit = {
            val now = clock.instant()
            if (now.isBefore(nextPullAllowed)) {
              val pendingTime = JavaDuration.between(now, nextPullAllowed)
              scheduleOnce("pull", DurationConverters.toScala(pendingTime))
            } else {
              doPull()
            }
          }
        }
      )

      private def doPull(): Unit = {
        pull(input)
        nextPullAllowed = clock.instant().plus(DurationConverters.toJava(rate))
      }

      override def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case "pull" =>
            doPull()
          case other =>
            log.error(s"Bug! We received a timer key ${other} that was not of type TimerKey")
        }
      }
    }
}

object RateLimiterFlow {

  /**
    * A component similar to Akka stream's built-in throttle, with the important difference that it does not buffer a
    * single element while the rate is exceeded.
    *
    * @param rate The time to wait between each pull
    * @param clock Clock used to judge the current time when calculating delays. Note, advancing this clock has no effect on already-scheduled delays
    */
  def apply[U](rate: FiniteDuration, clock: Clock = Clock.systemUTC()): Flow[U, U, NotUsed] =
    Flow.fromGraph(new RateLimiterFlow[U](rate, clock))
}
