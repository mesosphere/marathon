package mesosphere

import com.typesafe.config.{Config, ConfigFactory}
import mesosphere.marathon.integration.setup.RestResult
import mesosphere.marathon.raml.{PodState, PodStatus}
import org.scalatest._
import org.scalatest.matchers.{BeMatcher, MatchResult}
import org.scalatest.time.{Minutes, Seconds, Span}

trait IntegrationTestLike extends UnitTestLike {
  override val timeLimit = Span(15, Minutes)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Seconds))

  // Integration tests using docker image provisioning with the Mesos containerizer need to be
  // run as root in a Linux environment. They have to be explicitly enabled through an env variable.
  val envVarRunMesosTests = "RUN_MESOS_INTEGRATION_TESTS"

  /**
    * Custom matcher for HTTP responses that print response body.
    * @param status The expected status code.
    */
  class RestResultMatcher(status: Int) extends BeMatcher[RestResult[_]] {
    def apply(left: RestResult[_]) =
      MatchResult(
        left.code == status,
        s"Response code was not $status but ${left.code} with body '${left.entityString}'",
        s"Response code was $status with body '${left.entityString}'"
      )
  }

  val Accepted = new RestResultMatcher(202)
  val BadGateway = new RestResultMatcher(502)
  val Created = new RestResultMatcher(201)
  val Conflict = new RestResultMatcher(409)
  val Deleted = new RestResultMatcher(202)
  val OK = new RestResultMatcher(200)
  val NotFound = new RestResultMatcher(404)
  val ServerError = new RestResultMatcher(500)

  /**
    * Custom pod status matcher for Marathon facade request results.
    *
    * {{{
    *   eventually { marathon.status(pod.id) should be(Stable) }
    * }}}
    *
    * @param expected The expected status.
    */
  class PodStatusMatcher(expected: PodState) extends BeMatcher[RestResult[PodStatus]] {
    def apply(left: RestResult[PodStatus]) =
      MatchResult(
        left.value.status == expected,
        s"Pod had status ${left.value} but $expected was expected",
        s"Pod status was ${left.value}"
      )
  }

  val Stable = new PodStatusMatcher(PodState.Stable)
}

abstract class IntegrationTest extends WordSpec with IntegrationTestLike

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike {
  protected override lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
       |akka.test.default-timeout=${patienceConfig.timeout.toMillis}
    """.stripMargin).withFallback(ConfigFactory.load())
}

abstract class AkkaIntegrationTest extends IntegrationTest with AkkaIntegrationTestLike
