package mesosphere.marathon
package stream

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.concurrent.duration.FiniteDuration

/**
  * Repeats the given input periodically (defined by every), with the repeat timer beginning with each emission, up to a
  * count number of repeats.
  *
  * If there is no demand at the time of repeat, then the element is pushed as soon as there is demand again. Since the
  * repeat timer is set after the element is pushed, multiple repeat elements won't be queued up we don't
  *
  * Emits when upstream receives an element or the every duration has elapsed since last emitting
  * Backpressures when downstream backpressures
  * Completes when upstream completes
  * Cancels when downstream completes
  *
  * @param after - Duration after which an element is emitted that it should be emitted again
  */
class Repeater[T](after: FiniteDuration, count: Int = 1) extends GraphStage[FlowShape[T, T]] {
  val input = Inlet[T]("repeater-input")
  val output = Outlet[T]("repeater-output")
  val repeatTimer = "timer"

  override val shape = FlowShape(input, output)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    private var element: T = _
    private var pendingRepeats: Int = 0
    private var pendingPush = false

    setHandler(input, new InHandler {
      override def onUpstreamFinish(): Unit = {
        maybeComplete()
      }

      override def onPush(): Unit = {
        element = grab(input)
        pendingRepeats = count
        pendingPush = true
        cancelTimer(repeatTimer) // cancel the timer to repeat the last element, if it was scheduled
        pushLogic()
      }
    })

    setHandler(output, new OutHandler {
      override def onPull(): Unit = {
        pushLogic()
      }
    })

    override protected def onTimer(timerKey: Any): Unit = {
      if (timerKey == repeatTimer) {
        pendingPush = true
        pendingRepeats = pendingRepeats - 1
        pushLogic()
      }
    }

    override def preStart(): Unit = {
      pull(input)
    }

    def pushLogic(): Unit = {
      if (isAvailable(output) && pendingPush) {
        if (!hasBeenPulled(input)) tryPull(input)
        push(output, element)
        pendingPush = false
        if (pendingRepeats > 0)
          scheduleOnce(repeatTimer, after)
      }
      maybeComplete()
    }

    def maybeComplete(): Unit = {
      if (!pendingPush && isClosed(input)) {
        completeStage()
      }
    }
  }
}

object Repeater {
  def apply[T](every: FiniteDuration, count: Int = 1): Repeater[T] =
    new Repeater[T](every, count)
}
