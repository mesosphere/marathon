package mesosphere.marathon
package api

import java.util.concurrent.{ CountDownLatch, Semaphore, TimeUnit }
import javax.servlet.FilterChain
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.UnitTest
import mesosphere.marathon.core.async.ExecutionContexts.global

import scala.concurrent.Future

class LimitConcurrentRequestsFilterTest extends UnitTest {

  "LimitConcurrentRequestsFilter" should {
    "Multiple requests below boundary get answered correctly" in {
      Given("A http filter chain")
      val latch = new CountDownLatch(2)
      val request = mock[HttpServletRequest]
      val response = mock[HttpServletResponse]
      val chain = mock[FilterChain]
      chain.doFilter(request, response) answers { args => latch.countDown() }
      val rf = new LimitConcurrentRequestsFilter(Some(2))

      When("requests where made before the limit")
      Future(rf.doFilter(request, response, chain)) // linter:ignore:IdenticalStatements
      Future(rf.doFilter(request, response, chain))

      Then("The requests got answered")
      latch.await(5, TimeUnit.SECONDS) should be(true)
    }

    "Multiple requests above boundary get a 503" in {
      Given("A http filter chain")
      val semaphore = new Semaphore(1)
      val latch = new CountDownLatch(1)
      semaphore.acquire()
      val request = mock[HttpServletRequest]
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

    "If no limit is given, no semaphore is used" in {
      Given("A http filter chain with no limit")
      val latch = new CountDownLatch(1)
      val request = mock[HttpServletRequest]
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
  }
}
