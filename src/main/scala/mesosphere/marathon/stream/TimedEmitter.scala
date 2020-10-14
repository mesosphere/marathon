package mesosphere.marathon
package stream

import java.time.{Clock, Instant, Duration => JavaDuration}

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.collection.mutable.Queue
import scala.concurrent.duration._

/**
  * Given an element and some deadline, emits an event when that element is first received, and another event when the
  * deadline arrives
  *
  * Scenario A) Receiving event with no deadline marks as inactive and cancels the timer
  *   Given we receive:
  *     1. 00:00 - (Foo, Some(00:05))
  *     2. 00:03 - (Foo, None)
  *
  *   We emit:
  *
  *     1. 00:00 - Active(Foo)
  *     2. 00:03 - Inactive(Foo)
  *     (no further events received for Foo)
  *
  * Scenario B) Updating the event before the timer fires
  *
  *   Given we receive:
  *     1. 00:00 - (Foo, Some(00:05))
  *     2. 00:03 - (Foo, Some(00:10)
  *
  *   We emit:
  *
  *     1. 00:00 - Active(Foo)
  *     2. 00:03 - Active(Foo)
  *     3. 00:10 - Inactive(Foo)
  *
  * Scenario B) Cancelling when receiving a timestamp in the past
  *
  *   Given we receive:
  *     1. 00:00 - (Foo, Some(00:05))
  *     2. 00:03 - (Foo, Some(00:02)
  *
  *   We emit:
  *
  *     1. 00:00 - Active(Foo)
  *     2. 00:03 - Inactive(Foo)
  *     (no further events received for Foo)
  *
  * @tparam U Element type.
  */
class TimedEmitter[U](clock: Clock = Clock.systemUTC()) extends GraphStage[FlowShape[(U, Option[Instant]), TimedEmitter.EventState[U]]] {
  import TimedEmitter.{EventState, Inactive, Active}

  val input = Inlet[(U, Option[Instant])]("timed-emitter-input")
  val output = Outlet[EventState[U]]("timed-emitter-output")

  override val shape = FlowShape(input, output)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with StageLogging {

      case class ElementTimerKey(element: U)

      val queue = Queue.empty[EventState[U]]

      setHandler(
        input,
        new InHandler {
          override def onPush(): Unit = {
            val (element, maybeDeadline) = grab(input)

            val maybeDuration = maybeDeadline.map { JavaDuration.between(clock.instant(), _) }.filterNot(_.isNegative)

            maybeDuration match {
              case Some(duration) =>
                queue.enqueue(Active(element))
                scheduleOnce(ElementTimerKey(element), duration.toMillis.millis)
              case None =>
                cancelTimer(ElementTimerKey(element)) // This guarantees the timer will not be called if it hasn't already
                queue.enqueue(Inactive(element))
            }
            pushAndPullLogic()
          }
        }
      )

      setHandler(
        output,
        new OutHandler {
          override def onPull(): Unit = {
            pushAndPullLogic()
          }
        }
      )

      override def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case ElementTimerKey(element) =>
            queue.enqueue(Inactive(element))
            pushAndPullLogic()
          case other =>
            log.error(s"Bug! We received a timer key ${other} that was not of type TimerKey")
        }
      }

      override def preStart(): Unit = {
        pull(input)
      }

      private def pushAndPullLogic(): Unit = {
        if (isAvailable(output) && queue.nonEmpty)
          push(output, queue.dequeue())
        if (queue.isEmpty && !hasBeenPulled(input))
          pull(input)
      }
    }
}

object TimedEmitter {
  type Input[U] = (U, Option[Instant])
  def flow[U]: Flow[Input[U], EventState[U], NotUsed] =
    Flow.fromGraph(new TimedEmitter[U])

  sealed trait EventState[U] { def element: U }
  case class Active[U](element: U) extends EventState[U]
  case class Inactive[U](element: U) extends EventState[U]
}
