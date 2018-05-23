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
  * Associated AsyncContext will be automatically completed under the following circumstances:
  *
  * - The upstream source stops for any reason (completion, or failure)
  * - The HTTP client hangs up, or some other error occurs.
  *
  * The asyncContext will not be closed if this stream ultimately does not get started (exception before or during
  * materialization). In that case, the expectation is for the servlet handler to close the asyncContext (currently
  * unknown if it does this or not)
  */
class ServletOutputStreamSink(asyncContext: AsyncContext) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] with StrictLogging {

  private val outputStream = asyncContext.getResponse.getOutputStream
  private val started = new AtomicBoolean(false)
  private val in: Inlet[ByteString] = Inlet("ServletOutputStreamSink")
  override val shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      private var flushing = false

      val writePossible = createAsyncCallback[Unit] { _ =>
        flushOrMaybePull()
      }

      val writerFailed = createAsyncCallback[Throwable] { ex =>
        doFail(ex)
      }

      private def doFail(ex: Throwable): Unit = {
        failStage(ex)
        promise.failure(ex)
        Try(outputStream.close())
        Try(asyncContext.complete())
      }

      override def preStart(): Unit =
        if (started.compareAndSet(false, true)) {
          try {
            outputStream.setWriteListener(new WriteListener {
              override def onWritePossible(): Unit = {
                writePossible.invoke(())
              }

              override def onError(t: Throwable): Unit = {
                logger.error("Error in outputStream", t)
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

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          writeLogic(grab(in))
        }

        override def onUpstreamFinish(): Unit = {
          promise.success(Done)
          outputStream.close()
          asyncContext.complete()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          logger.error("upstream is failed", ex)
          doFail(ex)
        }
      })

      private def maybePull(): Unit =
        if (!isClosed(in)) {
          pull(in)
        }

      /**
        * Set mode to flushing. Returns true if ready for write, false if not.
        */
      private def flushLogic(): Boolean =
        if (outputStream.isReady()) {
          flushing = false
          outputStream.flush()
          outputStream.isReady()
        } else {
          flushing = true
          false
        }

      private def flushOrMaybePull(): Unit = {
        if (!flushing || flushLogic())
          maybePull()
      }

      private def writeLogic(data: ByteString): Unit = {
        require(!flushing, "Error! Should not get here!")
        outputStream.write(data.toArray)
        if (flushLogic()) {
          maybePull()
        } else {
          // The WriteListener will call maybePull()
        }
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
