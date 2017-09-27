package mesosphere.marathon
package stream

import akka.Done
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision
import akka.stream.stage.{ GraphStageLogic, InHandler, GraphStageWithMaterializedValue }
import akka.stream.{ Attributes, Inlet, SinkShape }
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
  * Stateful ForEach sink which receives a instantiator function, which is called upon materialization. This exists
  * because Akka streams does not provide a way to make a stateful element processor that is correlated with some
  * materialized result.
  *
  * Instantiator function receives a Future which indicates the future of the stream. Function returns the foreach logic
  * to be invoked on each element, and the materialized result to provide.
  */
class ForEachMatSink[T, M](instantiator: (Future[Done]) => (T => Unit, M)) extends GraphStageWithMaterializedValue[SinkShape[T], M] {
  val input = Inlet[T]("Repeater.in")

  override def shape: SinkShape[T] = new SinkShape(input)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, M) = {
    val streamCompleted = Promise[Done]
    val (f, mat) = instantiator(streamCompleted.future)

    class ForEachMatSinkLogic extends GraphStageLogic(shape) {
      private def decider =
        inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      override def preStart(): Unit =
        pull(input)

      setHandler(input, new InHandler {
        override def onPush(): Unit = {
          try {
            f(grab(input))
            pull(input)
          } catch {
            case NonFatal(ex) ⇒ decider(ex) match {
              case Supervision.Stop => failStage(ex)
              case _ => pull(input)
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          streamCompleted.success(Done)
        }

        override def onUpstreamFailure(ex: Throwable): Unit =
          streamCompleted.failure(ex)
      })
    }

    (new ForEachMatSinkLogic, mat)
  }
}
