package mesosphere.marathon.core.readiness.impl

import mesosphere.marathon.core.readiness.{ ReadinessCheckResult, HttpResponse }
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonActorSupport
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import rx.lang.scala.Observable
import spray.http.{
  HttpResponse => SprayHttpResponse,
  MediaTypes,
  MediaType,
  ContentType,
  HttpHeaders,
  HttpEntity,
  StatusCodes
}

import scala.concurrent.Future
import scala.concurrent.duration.{ FiniteDuration, _ }

class ReadinessCheckExecutorImplTest
    extends FunSuite with Matchers with GivenWhenThen with ScalaFutures with MarathonActorSupport {
  test("terminates on immediate readiness") {
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

  test("terminates on eventual readiness") {
    val f = new Fixture {
      override def httpResponse: SprayHttpResponse = synchronized {
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

  test("continue on error") {
    val f = new Fixture {
      override def testableSprayHttpGet(check: ReadinessCheckSpec): Future[SprayHttpResponse] = synchronized {
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

  test("spray result to readiness check http response") {
    val f = new Fixture
    When("converting a spray response")
    val response = f.executor.sprayResponseToCheckResponse(f.httpOkResponse)
    Then("we get the expected response")
    response should equal(
      HttpResponse(
        status = f.httpOkResponse.status.intValue,
        contentType = f.httpOkResponse.headers.find(_.lowercaseName == "content-type").get.value,
        body = "Hi 0"
      )
    )
  }

  test("error result for exception") {
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

  class Fixture {
    lazy val check = ReadinessCheckSpec(
      taskId = Task.Id.forApp(PathId("/test")),
      checkName = "testCheck",
      url = "http://sample.url:123",
      interval = 3.seconds,
      timeout = 1.second,
      httpStatusCodesForReady = Set(StatusCodes.OK.intValue),
      preserveLastResponse = true
    )

    lazy val ticks = Observable.from(Iterable.fill(10000)(0))
    lazy val executor: ReadinessCheckExecutorImpl = new ReadinessCheckExecutorImpl()(system) {
      override private[impl] def intervalObservable(interval: FiniteDuration): Observable[_] = ticks
      override private[impl] def sprayHttpGet(check: ReadinessCheckSpec): Future[SprayHttpResponse] =
        testableSprayHttpGet(check)
    }

    def hiBody = synchronized { HttpEntity(s"Hi $httpGetCalls") }
    def httpOkResponse = SprayHttpResponse(
      headers = List(HttpHeaders.`Content-Type`(ContentType(MediaTypes.`text/plain`))),
      entity = hiBody
    )
    def httpNotOkResponse = httpOkResponse.copy(status = StatusCodes.InternalServerError)
    def httpResponse = httpOkResponse

    var httpGetCalls = 0
    def testableSprayHttpGet(check: ReadinessCheckSpec): Future[SprayHttpResponse] = synchronized {
      httpGetCalls += 1
      Future.successful(httpResponse)
    }
  }
}
