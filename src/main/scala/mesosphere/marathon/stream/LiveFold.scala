package mesosphere.marathon
package stream

import akka.stream.stage.{GraphStageLogic, InHandler, GraphStageWithMaterializedValue}
import akka.stream.{Attributes, Inlet, SinkShape}
import mesosphere.marathon.core.async.ExecutionContexts
import scala.concurrent.{Future, Promise}

import LiveFold.Folder

/**
  * Sink which behaves like Fold but allows outsiders to query the current result of the fold via the materialized value
  */
class LiveFold[T, U](zero: U)(fold: (U, T) => U) extends GraphStageWithMaterializedValue[SinkShape[T], Folder[U]] {
  val input = Inlet[T]("Input data")

  override def shape: SinkShape[T] = new SinkShape(input)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Folder[U]) = {
    class SubjectGraphStageLogic extends GraphStageLogic(shape) {
      private var currentState = zero
      protected val finalResult = Promise[U]

      val readState = this.getAsyncCallback[Promise[U]] { p =>
        p.success(currentState)
      }

      val finalResultFuture: Future[U] = finalResult.future

      override def preStart(): Unit =
        pull(input)

      setHandler(
        input,
        new InHandler {
          override def onPush(): Unit = {
            try {
              currentState = fold(currentState, grab(input))
              pull(input)
            } catch {
              case ex: Throwable =>
                finalResult.failure(ex)
                failStage(ex)
            }
          }

          override def onUpstreamFinish(): Unit = {
            // trySuccess as fold function could have thrown an exception and failed the promise already
            finalResult.trySuccess(currentState)
          }
          override def onUpstreamFailure(ex: Throwable): Unit = {
            // tryFailure as fold function could have thrown an exception and failed the promise already
            finalResult.tryFailure(ex)
          }
        }
      )
    }

    val logic = new SubjectGraphStageLogic

    val output = new Folder[U] {
      override def readCurrentResult(): Future[U] = {
        val p = Promise[U]
        val done = logic.readState.invokeWithFeedback(p)
        done.failed.foreach { t =>
          p.tryFailure(t)
        }(ExecutionContexts.callerThread)

        p.future
      }
      override val finalResult = logic.finalResultFuture
    }
    (logic, output)
  }
}

object LiveFold {
  trait Folder[U] {
    def readCurrentResult(): Future[U]
    val finalResult: Future[U]
  }
}
