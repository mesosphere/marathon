package mesosphere.util

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.integration.setup.WaitTestSupport
import mesosphere.marathon.test.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.duration._

class CachingTest extends MarathonSpec with Mockito with Matchers with GivenWhenThen {

  test("cache usage") {
    Given("A resource that uses Caching trait")
    object resource extends Caching[String] {
      override protected[this] def cacheExpiresAfter: FiniteDuration = 100.millis
      private var state: Int = 0
      def cachedFunc: String = cached("cachedFun") {
        val result = state
        state += 1
        result.toString
      }
    }

    When("A cached function is called")
    val res1 = resource.cachedFunc

    Then("the initial result should be 0")
    res1 should be ("0")

    And("The cache should contain the returned value")
    resource.cache.size() should be (1)
    resource.cache.getIfPresent("cachedFun") shouldEqual "0"

    When("When the cached function is called again")
    val res2 = resource.cachedFunc

    Then("The result should be the same")
    res2 shouldEqual res1

    When("Expiration timeout occurred")
    WaitTestSupport.waitUntil("Cache is empty", maxWait = 1.seconds){
      resource.cache.getIfPresent("cachedFun") == null
    }

    And("The cached function should return the updated internal state")
    val res3 = resource.cachedFunc
    res3 should be ("1")

    And("The cache should now contain the value from call after expiration timeout")
    resource.cache.size() should be (1)
    resource.cache.getIfPresent("cachedFun") shouldEqual "1"
  }

}