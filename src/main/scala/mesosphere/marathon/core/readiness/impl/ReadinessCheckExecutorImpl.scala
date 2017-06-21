package mesosphere.marathon
package core.readiness.impl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ MediaTypes, StatusCodes, HttpResponse => AkkaHttpResponse }
import akka.stream.Materializer
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckExecutor, ReadinessCheckResult }
import mesosphere.marathon.util.Timeout
import org.slf4j.LoggerFactory
import rx.lang.scala.Observable

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import scala.util.control.NonFatal

/**
  * A Akka-HTTP-based implementation of a ReadinessCheckExecutor.
  */
private[readiness] class ReadinessCheckExecutorImpl(implicit actorSystem: ActorSystem, materializer: Materializer)
    extends ReadinessCheckExecutor {

  import mesosphere.marathon.core.async.ExecutionContexts.global
  private[this] val log = LoggerFactory.getLogger(getClass)
  private implicit val scheduler = actorSystem.scheduler

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

    akkaHttpGet(check)
      .flatMap(akkaResponseToCheckResponse(_, check))
      .map(ReadinessCheckResult.forSpecAndResponse(check, _))
      .recover(exceptionToErrorResponse(check))
      .andThen { case Success(result) => log.info(result.summary) }
  }

  private[impl] def akkaResponseToCheckResponse(akkaResponse: AkkaHttpResponse, check: ReadinessCheckSpec): Future[HttpResponse] = {
    val contentType = akkaResponse.headers.collectFirst { case `Content-Type`(t) ⇒ t }
    akkaResponse.entity.toStrict(check.timeout).map { strict =>
      HttpResponse(
        status = akkaResponse.status.intValue,
        contentType = contentType.fold("")(_.mediaType.value),
        body = strict.data.decodeString("utf-8")
      )
    }
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

  private[impl] def akkaHttpGet(check: ReadinessCheckSpec): Future[AkkaHttpResponse] = {
    Timeout(check.timeout)(Http().singleRequest(
      request = RequestBuilding.Get(check.url)
    ))
  }
}
