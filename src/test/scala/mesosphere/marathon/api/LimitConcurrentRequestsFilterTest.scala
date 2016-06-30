package mesosphere.marathon.api

import java.util.concurrent.{ TimeUnit, CountDownLatch, Semaphore }
import javax.servlet.FilterChain
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.marathon.MarathonSpec
import mesosphere.util.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LimitConcurrentRequestsFilterTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("Multiple requests below boundary get answered correctly") {
    Given("A http filter chain")
    val latch = new CountDownLatch(2)
    val request = mock[HttpServletRequest]
    request.getMethod returns "POST"
    val response = mock[HttpServletResponse]
    val chain = mock[FilterChain]
    chain.doFilter(request, response) answers { args => latch.countDown() }
    val rf = new LimitConcurrentRequestsFilter(Some(2))

    When("requests where made before the limit")
    Future(rf.doFilter(request, response, chain))
    Future(rf.doFilter(request, response, chain))

    Then("The requests got answered")
    latch.await(5, TimeUnit.SECONDS) should be(true)
  }

  test("Multiple requests above boundary get a 503") {
    Given("A http filter chain")
    val semaphore = new Semaphore(1)
    val latch = new CountDownLatch(1)
    semaphore.acquire()
    val request = mock[HttpServletRequest]
    request.getMethod returns "POST"
    val response = mock[HttpServletResponse]
    val chain = mock[FilterChain]
    chain.doFilter(request, response) answers { args => latch.countDown(); semaphore.acquire() /* blocks*/ }
    val rf = new LimitConcurrentRequestsFilter(Some(1))

    When("requests where made before the limit")
    Future(rf.doFilter(request, response, chain))
    latch.await(5, TimeUnit.SECONDS) should be(true) //make sure, first "request" has passed
    rf.doFilter(request, response, chain) //no future, since that should fail synchronously

    Then("The requests got answered")
    verify(chain, times(1)).doFilter(request, response)
    verify(response, times(1)).sendError(503, "Too many concurrent requests! Allowed: 1.")
    semaphore.release() //release the blocked thread
  }

  test("If no limit is given, no semaphore is used") {
    Given("A http filter chain with no limit")
    val latch = new CountDownLatch(1)
    val request = mock[HttpServletRequest]
    request.getMethod returns "POST"
    val response = mock[HttpServletResponse]
    val chain = mock[FilterChain]
    chain.doFilter(request, response) answers { args => latch.countDown() }
    val rf = new LimitConcurrentRequestsFilter(None)
    rf.semaphore.availablePermits() should be(0)

    When("A request is made")
    Future(rf.doFilter(request, response, chain))

    Then("Even the semaphore is 0 the request can be made and the pass function is used")
    latch.await(5, TimeUnit.SECONDS) should be(true)
  }

  test("GET requests are not throttled") {
    Given("A http filter chain")
    val semaphore = new Semaphore(0)
    val request = mock[HttpServletRequest]
    request.getMethod returns "GET"
    val response = mock[HttpServletResponse]
    val chain = mock[FilterChain]
    val requestsStarted = new CountDownLatch(2)
    chain.doFilter(request, response) answers { args =>
      requestsStarted.countDown()
      semaphore.acquire() /* blocks*/
    }
    val rf = new LimitConcurrentRequestsFilter(Some(1))

    When("more requests were made than the limit allows")
    val requestsFinished = new CountDownLatch(2)
    Future {
      rf.doFilter(request, response, chain)
      requestsFinished.countDown()
    }
    Future {
      rf.doFilter(request, response, chain)
      requestsFinished.countDown()
    }

    Then("they were accepted at the same time")
    requestsStarted.await()

    And("the available permits remained unaffected")
    rf.semaphore.availablePermits() should be(1)

    And("both requests finish as expected")
    semaphore.release(2)
    requestsFinished.await()

    verify(chain, times(2)).doFilter(request, response)
  }
}
