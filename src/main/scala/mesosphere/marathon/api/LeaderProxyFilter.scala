package mesosphere.marathon.api

import scala.collection.JavaConverters._
import com.google.inject.Inject
import java.net.{ HttpURLConnection, URL }
import java.io.{ OutputStream, InputStream }
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named
import javax.servlet._
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import mesosphere.marathon.{ MarathonSchedulerService, ModuleNames }
import org.apache.log4j.Logger

/**
  * Servlet filter that proxies requests to the leader if we are not the leader.
  *
  * @param schedulerService
  * @param leader
  */
class LeaderProxyFilter @Inject() (schedulerService: MarathonSchedulerService,
                                   @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean)
    extends Filter {

  val log = Logger.getLogger(getClass.getName)

  def init(filterConfig: FilterConfig): Unit = {}

  def copy(input: InputStream, output: OutputStream): Unit = {
    val bytes = new Array[Byte](1024)
    Iterator
      .continually(input.read(bytes))
      .takeWhile(_ != -1)
      .foreach(read => output.write(bytes, 0, read))
  }

  def buildUrl(leaderData: String, request: HttpServletRequest): URL = {
    // TODO handle https
    if (request.getQueryString != null) {
      new URL("http://%s%s?%s".format(leaderData, request.getRequestURI, request.getQueryString))
    }
    else {
      new URL("http://%s%s".format(leaderData, request.getRequestURI))
    }
  }

  def doFilter(rawRequest: ServletRequest,
               rawResponse: ServletResponse,
               chain: FilterChain) {

    if (rawRequest.isInstanceOf[HttpServletRequest]) {
      val request = rawRequest.asInstanceOf[HttpServletRequest]
      val response = rawResponse.asInstanceOf[HttpServletResponse]

      // Proxying occurs if we aren't in the leadership position and we know about the other leader (to proxy to).
      if (schedulerService.isLeader || schedulerService.getLeader.isEmpty) {
        chain.doFilter(request, response)
      }
      else {
        try {
          val leaderData = schedulerService.getLeader

          log.info(s"Proxying request to leader at ${leaderData.get}")

          val method = request.getMethod

          val proxy =
            buildUrl(leaderData.get, request)
              .openConnection().asInstanceOf[HttpURLConnection]

          val names = request.getHeaderNames
          // getHeaderNames() and getHeaders() are known to return null, see:
          //http://docs.oracle.com/javaee/6/api/javax/servlet/http/HttpServletRequest.html#getHeaders(java.lang.String)
          if (names != null) {
            for (name <- names.asScala) {
              val values = request.getHeaders(name)
              if (values != null) {
                proxy.setRequestProperty(name, values.asScala.mkString(","))
              }
            }
          }

          proxy.setRequestMethod(method)

          method match {
            case "GET" | "HEAD" | "DELETE" =>
              proxy.setDoOutput(false)
            case _ =>
              proxy.setDoOutput(true)
              val proxyOutputStream = proxy.getOutputStream
              copy(request.getInputStream, proxyOutputStream)
              proxyOutputStream.close
          }

          val status = proxy.getResponseCode
          response.setStatus(status)

          val responseOutputStream = response.getOutputStream

          try {
            val fields = proxy.getHeaderFields
            // getHeaderNames() and getHeaders() are known to return null
            if (fields != null) {
              for ((name, values) <- fields.asScala) {
                if (name != null && values != null) {
                  for (value <- values.asScala) {
                    response.setHeader(name, value)
                  }
                }
              }
            }
            copy(proxy.getInputStream, response.getOutputStream)
            proxy.getInputStream.close()
          }
          catch {
            case e: Exception =>
              copy(proxy.getErrorStream, response.getOutputStream)
              proxy.getErrorStream().close()
          }
          finally {
            responseOutputStream.close()
          }

        }
        catch {
          case e: Exception =>
            log.warn("Exception while proxying", e)
        }
      }
    }
  }

  def destroy() {
    //NO-OP
  }
}
