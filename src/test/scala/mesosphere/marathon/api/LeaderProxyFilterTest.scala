package mesosphere.marathon
package api

import java.io.IOException
import java.net.{ HttpURLConnection, URL }
import javax.servlet.FilterChain
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.UnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.election.ElectionService
import akka.http.scaladsl.model.StatusCodes._
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf

class LeaderProxyFilterTest extends UnitTest {

  def httpConf(args: String*): HttpConf = {
    new ScallopConf(args) with HttpConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()
    }
  }

  case class Fixture(
      conf: HttpConf = httpConf(),
      electionService: ElectionService = mock[ElectionService]("electionService"),
      forwarder: RequestForwarder = mock[RequestForwarder]("forwarder"),
      request: HttpServletRequest = mock[HttpServletRequest]("request"),
      response: HttpServletResponse = mock[HttpServletResponse]("response"),
      chain: FilterChain = mock[FilterChain]("chain")) {
    val filter = new LeaderProxyFilter(conf, electionService, "host:10000", forwarder) {
      override def sleep() = {}
    }

    def verifyClean(): Unit = verifyNoMoreInteractions(electionService, forwarder, request, response, chain)
  }

  "LeaderProxyFilter" should {
    "we are leader" in new Fixture {
      // When we are leader
      when(electionService.isLeader).thenReturn(true)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, "http://host:10000")
      verify(electionService, times(1)).isLeader
      verify(chain, times(1)).doFilter(request, response)
      verifyClean()
    }

    "try to wait for leadership info then give up" in new Fixture {
      // When we are leader but there are not other options
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(None)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(12)).isLeader
      verify(electionService, times(12)).leaderHostPort
      verify(response, times(1))
        .sendError(ServiceUnavailable.intValue, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
      verifyClean()
    }

    "forward to leader without query string" in new Fixture {
      // When someone else is leader
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(Some("otherhost:9999"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn(null)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(1)).isLeader
      verify(electionService, times(1)).leaderHostPort
      verify(request, atLeastOnce).getRequestURI
      verify(request, atLeastOnce).getQueryString
      verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test"), request, response)
      verifyClean()
    }

    "forward to leader with query string" in new Fixture {
      // When someone else is leader
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(Some("otherhost:9999"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn("argument=blieh")

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(1)).isLeader
      verify(electionService, times(1)).leaderHostPort
      verify(request, atLeastOnce).getRequestURI
      verify(request, atLeastOnce).getQueryString
      verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test?argument=blieh"), request, response)
      verifyClean()
    }

    "use https if http is disabled" in new Fixture(conf = httpConf("--disable_http")) {
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(Some("otherhost:9999"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn(null)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(1)).isLeader
      verify(electionService, times(1)).leaderHostPort
      verify(request, atLeastOnce).getRequestURI
      verify(request, atLeastOnce).getQueryString
      verify(forwarder, times(1)).forward(new URL("https://otherhost:9999/test"), request, response)
      verifyClean()
    }

    "successfully wait for consistent leadership info, then someone else is the leader" in new Fixture {
      // When we have inconsistent leadership info
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(Some("host:10000"), Some("host:10000"), Some("otherhost:9999"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn(null)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(4)).isLeader
      verify(electionService, times(4)).leaderHostPort
      verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test"), request, response)
      verify(request, atLeastOnce).getRequestURI
      verify(request, atLeastOnce).getQueryString
      verifyClean()
    }

    "successfully wait for consistent leadership info, then we are leader" in new Fixture {
      // When we have inconsistent leadership info
      when(electionService.isLeader).thenReturn(false, false, true)
      when(electionService.leaderHostPort).thenReturn(Some("host:10000"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn(null)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(4)).isLeader
      verify(electionService, times(3)).leaderHostPort
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, "http://host:10000")
      verify(chain, times(1)).doFilter(request, response)
      verifyClean()
    }

    "give up waiting for consistent leadership info" in new Fixture {
      // When we have inconsistent leadership info
      when(electionService.isLeader).thenReturn(false)
      when(electionService.leaderHostPort).thenReturn(Some("host:10000"))
      when(request.getRequestURI).thenReturn("/test")
      when(request.getQueryString).thenReturn(null)

      // And doFilter is called
      filter.doFilter(request, response, chain)

      // we pass that request down the chain
      verify(electionService, times(12)).isLeader
      verify(electionService, times(12)).leaderHostPort
      verify(response, times(1))
        .sendError(ServiceUnavailable.intValue, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
      verifyClean()
    }

    "bad proxy connection, drops partially complete response after status code has already been sent" in new Fixture {
      val urlConnection = mock[HttpURLConnection]

      when(urlConnection.getResponseCode).thenReturn(200)

      JavaUrlConnectionRequestForwarder.copyConnectionResponse(response)(
        () => {
          response.setStatus(200)
          scala.util.Failure(new IOException("foo"))
        },
        () => {}
      )

      verify(response, times(1)).setStatus(200)
      verify(response, times(1))
        .sendError(BadGateway.intValue, JavaUrlConnectionRequestForwarder.ERROR_STATUS_BAD_CONNECTION)
      verifyNoMoreInteractions(response)
      verifyClean()
    }
  }
}
