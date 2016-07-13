package mesosphere.marathon.stream

import akka.stream.{ Attributes, Inlet, SinkShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }

/**
  * Akka Streaming Graph Stage that collects a set of values into the given collection
  * Based on akka's SeqStage
  */
private final class CollectionStage[T, C](buf: mutable.Builder[T, C])
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[C]] {
  val in = Inlet[T]("collection.in")

  override def toString: String = "collectionStage"

  override val shape: SinkShape[T] = SinkShape.of(in)

  override protected def initialAttributes: Attributes = Attributes.name("setSink")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[C]) = {
    val promise = Promise[C]()
    val logic = new GraphStageLogic(shape) {

      override def preStart(): Unit = pull(in)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          buf += grab(in)
          pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          promise.trySuccess(buf.result())
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          promise.tryFailure(ex)
          failStage(ex)
        }
      })
    }
    (logic, promise.future)
  }
}
