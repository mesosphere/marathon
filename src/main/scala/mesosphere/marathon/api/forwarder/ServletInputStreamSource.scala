package mesosphere.marathon
package api.forwarder

import akka.NotUsed
import akka.stream.Attributes
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Outlet, SourceShape}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.atomic.AtomicBoolean
import javax.servlet.{AsyncContext, ReadListener}
import scala.util.Try

/**
  * Graph stage which implements an non-blocking IO ServletInputStream reader, following the protocol outlined here:
  *
  *   http://www.oracle.com/webfolder/technetwork/tutorials/obe/java/HTML5andServlet31/HTML5andServlet%203.1.html#section4
  *
  * The following runtime restrictions apply:
  *
  * - This Source cannot be materialized twice
  * - No other readers for this inputStream may exist (IE no other component may register a readListener)
  * - The associated context must be put in to async mode, first.
  *
  * Closes the inputStream when stage completes (exception or not). If stage is not materializated, has no effect on the
  * servlet's inputStream.
  */
class ServletInputStreamSource(asyncContext: AsyncContext, maxChunkSize: Int = 16384) extends GraphStage[SourceShape[ByteString]] with StrictLogging {

  private val started = new AtomicBoolean(false)
  private val inputStream = asyncContext.getRequest.getInputStream
  private val outlet: Outlet[ByteString] = Outlet("ServletInputStreamSource")
  override val shape: SourceShape[ByteString] = SourceShape(outlet)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    /**
      * Downstream has asked for data, and we have not yet pushed yet
      */
    private var pullPending = false
    /**
      * The inputStream has advertised that more data is available, and we have not read it yet.
      */
    private var readPending = false

    private val readBuffer = Array.ofDim[Byte](maxChunkSize)

    val readPossible = createAsyncCallback[Unit] { _ =>
      readPending = true
      maybeReadAndPush()
    }

    val allDone = createAsyncCallback[Unit] { _ =>
      completeStage()
    }

    val readerFailed = createAsyncCallback[Throwable] { ex =>
      doFail(ex)
    }

    private def doFail(ex: Throwable): Unit = {
      failStage(ex)
      Try(inputStream.close())
    }

    override def postStop(): Unit =
      inputStream.close()

    override def preStart(): Unit =
      if (started.compareAndSet(false, true)) {
        try {
          inputStream.setReadListener(new ReadListener {
            override def onDataAvailable(): Unit = {
              readPossible.invoke(())
            }

            override def onAllDataRead(): Unit = {
              allDone.invoke(())
            }

            override def onError(t: Throwable): Unit = {
              logger.error("Error in inputStream", t)
              readerFailed.invoke(t)
            }
          })
        } catch {
          case ex: Throwable =>
            doFail(ex)
        }
      } else {
        doFail(new IllegalStateException("This source can only be materialized once."))
      }

    setHandler(outlet, new OutHandler {
      override def onPull(): Unit = {
        pullPending = true
        maybeReadAndPush()
      }

      override def onDownstreamFinish(): Unit = {
        completeStage()
      }
    })

    private def maybeReadAndPush(): Unit = {
      if (readPending && pullPending) {
        val len = inputStream.read(readBuffer)
        if (len == -1)
          completeStage()
        else {
          push(outlet, ByteString.fromArray(readBuffer, 0, len))
          pullPending = false
          readPending = inputStream.isReady()
        }
      }
    }
  }

}

object ServletInputStreamSource {
  /**
    * Given an asyncContext, return a Source which reads from the Servlet request's input stream.
    *
    * See the constructor documentation for [[ServletInputStreamSource]]
    */
  def forAsyncContext(asyncContext: AsyncContext): Source[ByteString, NotUsed] =
    Source.fromGraph(new ServletInputStreamSource(asyncContext))

}
