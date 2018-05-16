package mesosphere.marathon
package api

import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletRequestWrapper, HttpServletResponse, HttpServletResponseWrapper}
import mesosphere.marathon.metrics.{Metrics, ServiceMetric}

/**
  * This filter replaces the default I/O streams with proxies that count
  * the number of bytes that are sent to and from the client.
  */
class HTTPMetricsFilter extends Filter {
  private[this] val bytesReadMetric = Metrics.counter(ServiceMetric, getClass, "bytesRead")
  private[this] val bytesWrittenMetric = Metrics.counter(ServiceMetric, getClass, "bytesWritten")

  /**
    * Wraps a `ServletOutputStream` and overrides the `write` method in
    * order to count the number of bytes that went through the stream.
    *
    * @param upstream The stream to proxy the output to
    */
  private class OutputStreamCounter(val upstream: ServletOutputStream) extends ServletOutputStream {
    var bytes: Int = 0

    override def isReady: Boolean = upstream.isReady
    override def setWriteListener(writeListener: WriteListener): Unit = upstream.setWriteListener(writeListener)
    override def write(b: Int): Unit = {
      upstream.write(b)
      bytes += 1
    }

    override def write(b: Array[Byte]): Unit = {
      upstream.write(b)
      bytes += b.length
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      upstream.write(b, off, len)
      bytes += len
    }
  }

  private class ResponseCounterWrapper(val r: HttpServletResponse) extends HttpServletResponseWrapper(r) {
    private lazy val stream = new OutputStreamCounter(super.getOutputStream)
    def bytesWritten: Int = stream.bytes

    /**
      * Override the default implementation in order to return the custom stream counter
      * @return The stream counter that wraps the base stream
      */
    override def getOutputStream: ServletOutputStream = stream
  }

  /**
    * Wraps a `ServletOutputStream` and overrides the `write` method in
    * order to count the number of bytes that went through the stream.
    *
    * @param upstream The stream to proxy the output to
    */
  private class InputStreamCounter(val upstream: ServletInputStream) extends ServletInputStream {
    var bytes: Int = 0

    override def isReady: Boolean = upstream.isReady
    override def setReadListener(readListener: ReadListener): Unit = upstream.setReadListener(readListener)
    override def isFinished: Boolean = upstream.isFinished
    override def read(): Int = {
      val byte = upstream.read()
      if (byte != -1) {
        bytes += 1
      }
      byte
    }

    override def read(b: Array[Byte]): Int = {
      val bytesRead = upstream.read(b)
      if (bytesRead != -1) {
        bytes += bytesRead
      }
      bytesRead
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      val bytesRead = upstream.read(b, off, len)
      if (bytesRead != -1) {
        bytes += bytesRead
      }
      bytesRead
    }
  }

  private class RequestCounterWrapper(val r: HttpServletRequest) extends HttpServletRequestWrapper(r) {
    private lazy val stream = new InputStreamCounter(super.getInputStream)
    def bytesRead: Int = stream.bytes

    /**
      * Override the default implementation in order to return the custom stream counter
      * @return The stream counter that wraps the base stream
      */
    override def getInputStream: ServletInputStream = stream
  }

  /**
    * This is the entry point to the filter processing
    *
    * This function wraps the request and the response with a proxy class that is going to add counters
    * to the input and output streams.
    *
    * @param request
    * @param response
    * @param chain
    */
  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    val inputCounter = new RequestCounterWrapper(request.asInstanceOf[HttpServletRequest])
    val outputCounter = new ResponseCounterWrapper(response.asInstanceOf[HttpServletResponse])

    // The proxy classes should be as fast as possible and therefore should not commit any
    // metrics to Kamon, rather simply increase the counters
    chain.doFilter(inputCounter, outputCounter)

    // Since the filter processing is synchronous, when the execution reaches this point
    // the counters should be populated. This is where we push the values to Kamon.
    bytesReadMetric.increment(inputCounter.bytesRead)
    bytesWrittenMetric.increment(outputCounter.bytesWritten)
  }

  override def init(filterConfig: FilterConfig): Unit = {}
  override def destroy(): Unit = {}
}
