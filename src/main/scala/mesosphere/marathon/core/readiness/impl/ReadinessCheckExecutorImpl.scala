package mesosphere.marathon.core.readiness.impl

import akka.actor.ActorSystem
import akka.util.Timeout
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckExecutor, ReadinessCheckResult }
import org.slf4j.LoggerFactory
import rx.lang.scala.Observable
import spray.client.pipelining._
import spray.http.HttpHeaders.`Content-Type`
import spray.http.{ HttpResponse => SprayHttpResponse, _ }

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import scala.util.control.NonFatal

/**
  * A Spray-based implementation of a ReadinessCheckExecutor.
  */
private[readiness] class ReadinessCheckExecutorImpl(implicit actorSystem: ActorSystem)
    extends ReadinessCheckExecutor {

  import scala.concurrent.ExecutionContext.Implicits.global
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def execute(readinessCheckSpec: ReadinessCheckSpec): Observable[ReadinessCheckResult] = {
    def singleCheck(): Future[ReadinessCheckResult] = executeSingleCheck(readinessCheckSpec)

    intervalObservable(readinessCheckSpec.interval)
      .scan(singleCheck()) {
        case (last, next) =>
          // Serialize readiness check execution and repeat last on ready.
          // Note that the futures will usually never fail, therefore flatMap is ok.
          last.flatMap(result => if (result.ready) last else singleCheck())
      }
      .concatMap(Observable.from(_))
      .takeUntil(_.ready)
  }

  private[impl] def intervalObservable(interval: FiniteDuration): Observable[_] =
    Observable.interval(interval)

  private[impl] def executeSingleCheck(check: ReadinessCheckSpec): Future[ReadinessCheckResult] = {
    log.info(s"Querying ${check.taskId} readiness check '${check.checkName}' at '${check.url}'")

    sprayHttpGet(check)
      .map(sprayResponseToCheckResponse)
      .map(ReadinessCheckResult.forSpecAndResponse(check, _))
      .recover(exceptionToErrorResponse(check))
      .andThen { case Success(result) => log.info(result.summary) }
  }

  private[impl] def sprayResponseToCheckResponse(sprayResponse: SprayHttpResponse): HttpResponse = {
    val contentType = sprayResponse.headers.collectFirst { case `Content-Type`(t) ⇒ t }
    HttpResponse(
      status = sprayResponse.status.intValue,
      contentType = contentType.fold("")(_.mediaType.value),
      body = sprayResponse.entity.asString
    )
  }

  private[impl] def exceptionToErrorResponse(
    check: ReadinessCheckSpec): PartialFunction[Throwable, ReadinessCheckResult] = {
    case NonFatal(e) =>
      val response = HttpResponse(
        status = StatusCodes.GatewayTimeout.intValue,
        contentType = MediaTypes.`text/plain`.value,
        body = s"Marathon could not query ${check.url}: ${e.getMessage}"
      )

      ReadinessCheckResult.forSpecAndResponse(check, response).copy(ready = false)
  }

  private[impl] def sprayHttpGet(check: ReadinessCheckSpec): Future[SprayHttpResponse] = {
    implicit val requestTimeout = Timeout(check.timeout)
    val pipeline: HttpRequest => Future[SprayHttpResponse] = sendReceive
    pipeline(Get(check.url))
  }
}
