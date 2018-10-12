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
      protected var completed = Promise[U]

      val readState = this.getAsyncCallback[Promise[U]] { p =>
        p.success(currentState)
      }

      def completedResult = completed.future

      override def preStart(): Unit =
        pull(input)

      setHandler(input, new InHandler {
        override def onPush(): Unit = {
          currentState = fold(currentState, grab(input))
          pull(input)
        }

        override def onUpstreamFinish(): Unit = completed.success(currentState)
        override def onUpstreamFailure(ex: Throwable): Unit = completed.failure(ex)
      })
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
      override val finalResult = logic.completedResult
    }
    (new SubjectGraphStageLogic, output)
  }
}

object LiveFold {
  trait Folder[U] {
    def readCurrentResult(): Future[U]
    val finalResult: Future[U]
  }
}
