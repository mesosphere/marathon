package mesosphere.marathon
package api

import java.net.URL
import javax.servlet.FilterChain
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import mesosphere.AkkaUnitTest
import mesosphere.marathon.HttpConf
import mesosphere.marathon.api.forwarder.RequestForwarder
import mesosphere.marathon.core.election.ElectionService
import akka.http.scaladsl.model.StatusCodes._
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf

class LeaderProxyFilterTest extends AkkaUnitTest {

  def httpConf(args: String*): HttpConf = {
    new ScallopConf(args) with HttpConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()
    }
  }

  case class Fixture(
      disableHttp: Boolean = false,
      electionService: ElectionService = mock[ElectionService]("electionService"),
      forwarder: RequestForwarder = mock[RequestForwarder]("forwarder"),
      request: HttpServletRequest = mock[HttpServletRequest]("request"),
      response: HttpServletResponse = mock[HttpServletResponse]("response"),
      chain: FilterChain = mock[FilterChain]("chain")) {
    val filter = new LeaderProxyFilter(
      disableHttp = disableHttp,
      electionService = electionService,
      myHostPort = "host:10000",
      forwarder = forwarder) {
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
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_FRAME_OPTIONS, LeaderProxyFilter.VALUE_FRAME_OPTIONS)
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_XXS_PROTECTION, LeaderProxyFilter.VALUE_XXS_PROTECTION)

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
        .sendError(BadGateway.intValue, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
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

    "use https if http is disabled" in new Fixture(disableHttp = true) {
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
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_FRAME_OPTIONS, LeaderProxyFilter.VALUE_FRAME_OPTIONS)
      verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_XXS_PROTECTION, LeaderProxyFilter.VALUE_XXS_PROTECTION)
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
        .sendError(BadGateway.intValue, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
      verifyClean()
    }
  }
}
