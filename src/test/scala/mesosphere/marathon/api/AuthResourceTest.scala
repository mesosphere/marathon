package mesosphere.marathon.api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.Response

import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, Identity }
import mesosphere.marathon.plugin.http.{ HttpRequest, HttpResponse }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import mesosphere.marathon.{ AllConf, MarathonConf }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Future

class AuthResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {
  test("authenticated returns service unavailable if the authenticator returns an exception") {
    Given("An authenticator that always throws an exception")
    val f = new Fixture
    val resource = new TestResource(f.brokenAuthenticator, f.auth.auth, f.config)

    When("we try to authenticate a request")
    val response = resource.foo(f.auth.request)

    Then("we receive a service unavailable response")
    response.getStatus should be(Response.Status.SERVICE_UNAVAILABLE.getStatusCode)
  }

  test("authenticated returns the result of fn if the authenticator returns an identity") {
    Given("An authenticator that always throws an exception")
    val f = new Fixture
    val resource = new TestResource(f.auth.auth, f.auth.auth, f.config)

    When("we try to authenticate a request")
    val response = resource.foo(f.auth.request)

    Then("we receive a service unavailable response")
    response.getStatus should be (Response.Status.OK.getStatusCode)
    response.getEntity.asInstanceOf[String] should be ("foo")
  }

  class TestResource(val authenticator: Authenticator, val authorizer: Authorizer, val config: MarathonConf)
      extends AuthResource {
    def foo(request: HttpServletRequest): Response = authenticated(request) { identity =>
      Response.ok("foo").build()
    }
  }

  class Fixture {
    val config = AllConf.withTestConfig("--zk_timeout", "1")

    val auth = new TestAuthFixture

    val brokenAuthenticator: Authenticator = new Authenticator {
      override def authenticate(request: HttpRequest): Future[Option[Identity]] = {
        Future.failed(new RuntimeException("Foo"))
      }

      override def handleNotAuthenticated(request: HttpRequest, response: HttpResponse): Unit = {
        response.status(Response.Status.UNAUTHORIZED.getStatusCode)
      }
    }
  }
}
