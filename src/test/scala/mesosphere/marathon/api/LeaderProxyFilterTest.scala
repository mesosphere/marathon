package mesosphere.marathon.api

import java.net.URL
import javax.servlet.FilterChain
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonSpec
import org.apache.http.HttpStatus
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf

class LeaderProxyFilterTest extends MarathonSpec {

  def httpConf(args: String*): HttpConf = {
    val conf = new ScallopConf(args) with HttpConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    conf.afterInit()
    conf
  }

  var leaderInfo: LeaderInfo = _
  var forwarder: RequestForwarder = _
  var filter: LeaderProxyFilter = _
  var request: HttpServletRequest = _
  var response: HttpServletResponse = _
  var chain: FilterChain = _

  def init(conf: HttpConf = httpConf()) {
    leaderInfo = mock[LeaderInfo]("leaderInfo")
    forwarder = mock[RequestForwarder]("forwarder")
    filter = new LeaderProxyFilter(conf, leaderInfo, "host:10000", forwarder) {
      override def sleep() = {}
    }
    request = mock[HttpServletRequest]("request")
    response = mock[HttpServletResponse]("response")
    chain = mock[FilterChain]("chain")
  }

  after {
    verifyNoMoreInteractions(leaderInfo, forwarder, request, response, chain)
    leaderInfo = null
    forwarder = null
    filter = null
    request = null
    response = null
    chain = null
  }

  test("we are leader") {
    // When we are leader
    init()
    when(leaderInfo.elected).thenReturn(true)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, "http://host:10000")
    verify(leaderInfo, times(1)).elected
    verify(chain, times(1)).doFilter(request, response)
  }

  test("try to wait for leadership info then give up") {
    // When we are leader but there are not other options
    init()
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(None)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(12)).elected
    verify(leaderInfo, times(12)).currentLeaderHostPort()
    verify(response, times(1))
      .sendError(HttpStatus.SC_SERVICE_UNAVAILABLE, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
  }

  test("forward to leader without query string") {
    // When someone else is leader
    init()
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("otherhost:9999"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn(null)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(1)).elected
    verify(leaderInfo, times(1)).currentLeaderHostPort()
    verify(request, atLeastOnce()).getRequestURI
    verify(request, atLeastOnce()).getQueryString
    verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test"), request, response)
  }

  test("forward to leader with query string") {
    // When someone else is leader
    init()
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("otherhost:9999"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn("argument=blieh")

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(1)).elected
    verify(leaderInfo, times(1)).currentLeaderHostPort()
    verify(request, atLeastOnce()).getRequestURI
    verify(request, atLeastOnce()).getQueryString
    verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test?argument=blieh"), request, response)
  }

  test("use https if http is disabled") {
    // When someone else is leader
    init(conf = httpConf("--disable_http"))
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("otherhost:9999"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn(null)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(1)).elected
    verify(leaderInfo, times(1)).currentLeaderHostPort()
    verify(request, atLeastOnce()).getRequestURI
    verify(request, atLeastOnce()).getQueryString
    verify(forwarder, times(1)).forward(new URL("https://otherhost:9999/test"), request, response)
  }

  test("successfully wait for consistent leadership info, then someone else is the leader") {
    // When we have inconsistent leadership info
    init()
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("host:10000"), Some("host:10000"), Some("otherhost:9999"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn(null)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(4)).elected
    verify(leaderInfo, times(4)).currentLeaderHostPort()
    verify(forwarder, times(1)).forward(new URL("http://otherhost:9999/test"), request, response)
    verify(request, atLeastOnce()).getRequestURI
    verify(request, atLeastOnce()).getQueryString
  }

  test("successfully wait for consistent leadership info, then we are leader") {
    // When we have inconsistent leadership info
    init()
    when(leaderInfo.elected).thenReturn(false, false, true)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("host:10000"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn(null)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(4)).elected
    verify(leaderInfo, times(3)).currentLeaderHostPort()
    verify(response, times(1)).addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, "http://host:10000")
    verify(chain, times(1)).doFilter(request, response)
  }

  test("give up waiting for consistent leadership info") {
    // When we have inconsistent leadership info
    init()
    when(leaderInfo.elected).thenReturn(false)
    when(leaderInfo.currentLeaderHostPort()).thenReturn(Some("host:10000"))
    when(request.getRequestURI).thenReturn("/test")
    when(request.getQueryString).thenReturn(null)

    // And doFilter is called
    filter.doFilter(request, response, chain)

    // we pass that request down the chain
    verify(leaderInfo, times(12)).elected
    verify(leaderInfo, times(12)).currentLeaderHostPort()
    verify(response, times(1))
      .sendError(HttpStatus.SC_SERVICE_UNAVAILABLE, LeaderProxyFilter.ERROR_STATUS_NO_CURRENT_LEADER)
  }
}
