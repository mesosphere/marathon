package mesosphere.marathon
package core.readiness.impl

import akka.actor.{ActorSystem, Cancellable}
import akka.pattern.after
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{MediaTypes, StatusCodes, HttpResponse => AkkaHttpResponse}
import akka.stream.scaladsl.Keep
import akka.stream.{KillSwitches, Materializer}
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.readiness.{HttpResponse, ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.util.{CancellableOnce, Timeout}

import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal

/**
  * A Akka-HTTP-based implementation of a ReadinessCheckExecutor.
  */
private[readiness] class ReadinessCheckExecutorImpl(implicit actorSystem: ActorSystem, materializer: Materializer)
  extends ReadinessCheckExecutor with StrictLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  private[readiness] implicit val scheduler = actorSystem.scheduler

  /**
    * Iterator which lazily invokes an endless series of serial readiness checks, with delay between invocations.
    *
    * Once a check returns ready, this result is used forever.
    *
    * First check is eager. Next check will not commence until after both 1) first check completes, and 2)
    * iterator.next() is called
    */
  private def serialChecksIterator(spec: ReadinessCheckSpec) = Iterator.iterate(executeSingleCheck(spec)) { result =>
    result.flatMap { r =>
      if (r.ready)
        result
      else
        after(spec.interval, scheduler) { executeSingleCheck(spec) }
    }
  }

  /**
    * Returns an akka stream source that, when materializes, runs readiness checks periodically according to the spec.
    *
    * No checks are executed until the source is materialized.
    *
    * When the readiness check succeeds, the last element will be the successful result, followed by the source
    * completing.
    */
  override def execute(readinessCheckSpec: ReadinessCheckSpec): Source[ReadinessCheckResult, Cancellable] = {
    Source.fromIterator(() => serialChecksIterator(readinessCheckSpec))
      .mapAsync(1)(identity)
      .takeWhile({ result => !result.ready }, inclusive = true)
      .viaMat(KillSwitches.single)(Keep.right)
      .mapMaterializedValue { killSwitch => new CancellableOnce(() => killSwitch.shutdown()) }
  }

  private[impl] def executeSingleCheck(check: ReadinessCheckSpec): Future[ReadinessCheckResult] = {
    logger.info(s"Querying ${check.taskId} readiness check '${check.checkName}' at '${check.url}'")

    akkaHttpGet(check)
      .flatMap(akkaResponseToCheckResponse(_, check))
      .map(ReadinessCheckResult.forSpecAndResponse(check, _))
      .recover(exceptionToErrorResponse(check))
      .andThen { case Success(result) => logger.info(result.summary) }
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
