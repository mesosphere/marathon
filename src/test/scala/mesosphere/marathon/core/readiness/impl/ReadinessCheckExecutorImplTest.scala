package mesosphere.marathon
package core.readiness.impl

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import rx.lang.scala.Observable
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpHeader, StatusCodes, HttpResponse => AkkaHttpResponse }

import scala.concurrent.Future
import scala.concurrent.duration.{ FiniteDuration, _ }

class ReadinessCheckExecutorImplTest extends AkkaUnitTest {
  "ReadinessCheckExecutorImpl" should {
    "terminates on immediate readiness" in {
      val f = new Fixture

      When("querying readiness which immediately responds with ready")
      val readinessResultsObservable = f.executor.execute(f.check)
      val readinessResults = readinessResultsObservable.toBlocking.toList

      Then("the observable terminates with exactly one result")
      readinessResults should have size 1
      And("it indicates readiness")
      readinessResults.head.ready should be(true)
      And("httpGet should not have been called more then necessary")
      f.httpGetCalls should equal(1)
    }

    "terminates on eventual readiness" in {
      val f = new Fixture {
        override def httpResponse: AkkaHttpResponse = synchronized {
          if (httpGetCalls < 5) httpNotOkResponse
          else httpOkResponse
        }
      }

      When("querying readiness")
      val readinessResultsObservable = f.executor.execute(f.check)
      val readinessResults = readinessResultsObservable.toBlocking.toList

      Then("the observable terminates with five results")
      readinessResults should have size 5
      And("the last result is ready")
      readinessResults.last.ready should be(true)
      And("all other results are not ready")
      readinessResults.take(4).exists(_.ready) should be(false)
      And("httpGet should not have been called more then necessary")
      f.httpGetCalls should equal(5)
    }

    "continue on error" in {
      val f = new Fixture {
        override def testableAkkaHttpGet(check: ReadinessCheckSpec): Future[AkkaHttpResponse] = synchronized {
          httpGetCalls += 1
          if (httpGetCalls < 5) Future.failed(new RuntimeException("temporary failure"))
          else Future.successful(httpOkResponse)
        }

      }

      When("querying readiness")
      val readinessResultsObservable = f.executor.execute(f.check)
      val readinessResults = readinessResultsObservable.toBlocking.toList

      Then("the observable terminates with five results")
      readinessResults should have size 5
      And("the last result is ready")
      readinessResults.last.ready should be(true)
      And("all other results are not ready")
      readinessResults.take(4).exists(_.ready) should be(false)
      And("httpGet should not have been called more then necessary")
      f.httpGetCalls should equal(5)
    }

    "akka http result to readiness check http response" in {
      val f = new Fixture
      When("converting a akka http response")
      val response = f.executor.akkaResponseToCheckResponse(f.httpOkResponse, f.check).futureValue
      Then("we get the expected response")
      response should equal(
        HttpResponse(
          status = f.httpOkResponse.status.intValue,
          contentType = "text/plain",
          body = "Hi 0"
        )
      )
    }

    "error result for exception" in {
      val f = new Fixture
      When("converting an error")
      val response = f.executor.exceptionToErrorResponse(f.check)(new RuntimeException("some failure"))
      Then("we get the expected response")
      response should equal(
        ReadinessCheckResult.forSpecAndResponse(
          f.check,
          HttpResponse(
            status = StatusCodes.GatewayTimeout.intValue,
            contentType = "text/plain",
            body = "Marathon could not query http://sample.url:123: some failure"
          )
        )
      )
    }
  }

  class Fixture {
    lazy val check = ReadinessCheckSpec(
      taskId = Task.Id.forRunSpec(PathId("/test")),
      checkName = "testCheck",
      url = "http://sample.url:123",
      interval = 3.seconds,
      timeout = 1.second,
      httpStatusCodesForReady = Set(StatusCodes.OK.intValue),
      preserveLastResponse = true
    )

    lazy val ticks = Observable.from(Seq.fill(10000)(0))
    lazy val executor: ReadinessCheckExecutorImpl = new ReadinessCheckExecutorImpl()(system, mat) {
      override private[impl] def intervalObservable(interval: FiniteDuration): Observable[_] = ticks
      override private[impl] def akkaHttpGet(check: ReadinessCheckSpec): Future[AkkaHttpResponse] =
        testableAkkaHttpGet(check)
    }

    def hiBody = synchronized { HttpEntity(s"Hi $httpGetCalls") }
    def httpOkResponse = AkkaHttpResponse(
      headers = Seq[HttpHeader](akka.http.scaladsl.model.headers.`Content-Type`(ContentTypes.`text/plain(UTF-8)`)),
      entity = hiBody
    )
    def httpNotOkResponse = httpOkResponse.copy(status = StatusCodes.InternalServerError)
    def httpResponse = httpOkResponse

    var httpGetCalls = 0
    def testableAkkaHttpGet(check: ReadinessCheckSpec): Future[AkkaHttpResponse] = synchronized { // linter:ignore:UnusedParameter
      httpGetCalls += 1
      Future.successful(httpResponse)
    }
  }
}
