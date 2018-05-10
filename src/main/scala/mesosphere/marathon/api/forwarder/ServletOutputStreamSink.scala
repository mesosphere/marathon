package mesosphere.marathon
package api.forwarder

import akka.stream.scaladsl.Sink
import akka.stream.stage.{GraphStageLogic, InHandler}
import akka.stream.{Attributes, Inlet}
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.Done
import akka.stream.SinkShape
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.atomic.AtomicBoolean
import javax.servlet.{AsyncContext, WriteListener}
import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * Graph stage which implements a non-blocking io ServletOutupStream writer, following the protocol outlined here:
  *
  * http://www.oracle.com/webfolder/technetwork/tutorials/obe/java/HTML5andServlet31/HTML5andServlet%203.1.html#section3
  *
  * The following runtime restrictions apply:
  *
  * - This Sink cannot be materialized twice
  * - No other writers for this outputStream may exist (IE no other component may register a writeListener)
  * - The associated context must be put in to async mode, first
  *
  * Materialized Future will fail if the outputStream is not upgraded to async, or if a writeListener is already
  * registered.
  *
  * Associated AsyncContext is not automatically closed; it is the responsible module using this sink to call
  * asyncContext.complete() AFTER the materialized future is completed (failure or success).
  */
class ServletOutputStreamSink(asyncContext: AsyncContext) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] with StrictLogging {

  private val outputStream = asyncContext.getResponse.getOutputStream
  private val started = new AtomicBoolean(false)
  private val in: Inlet[ByteString] = Inlet("ServletOutputStreamSink")
  override val shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      var flushPending = false
      val writePossible = createAsyncCallback[Unit] { _ =>
        flushOrPull()
      }

      val writerFailed = createAsyncCallback[Throwable] { ex =>
        doFail(ex)
      }

      override def preStart(): Unit =
        if (started.compareAndSet(false, true)) {
          try {
            outputStream.setWriteListener(new WriteListener {
              override def onWritePossible(): Unit = {
                writePossible.invoke(())
              }

              override def onError(t: Throwable): Unit = {
                writerFailed.invoke(t)
              }
            })
          } catch {
            case ex: Throwable =>
              doFail(ex)
          }
        } else {
          doFail(new IllegalStateException("This sink can only be materialized once."))
        }

      override def postStop(): Unit = {
        Try(outputStream.close())
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val contents = grab(in)
          outputStream.write(contents.toArray)
          flushPending = true
          if (outputStream.isReady())
            flushOrPull()
        }

        override def onUpstreamFinish(): Unit = {
          doComplete()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          doFail(ex)
        }
      })

      private def flushOrPull(): Unit = {
        if (flushPending) {
          flushPending = false
          outputStream.flush()
          if (outputStream.isReady())
            pull(in)
        } else {
          pull(in)
        }
      }

      private def doComplete(): Unit = {
        promise.success(Done)
        completeStage()
      }

      private def doFail(ex: Throwable): Unit = {
        failStage(ex)
        promise.failure(ex)
      }
    }

    (logic, promise.future)
  }
}

object ServletOutputStreamSink {
  /**
    * Given an asyncContext, return a Sink which writes to the Servlet request's output stream.
    *
    * See the constructor documentation for [[ServletOutputStreamSink]]
    */
  def forAsyncContext(asyncContext: AsyncContext): Sink[ByteString, Future[Done]] =
    Sink.fromGraph(new ServletOutputStreamSink(asyncContext))
}
