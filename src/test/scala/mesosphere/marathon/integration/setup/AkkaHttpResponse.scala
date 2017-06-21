package mesosphere.marathon
package integration.setup

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object AkkaHttpResponse {

  def request(request: HttpRequest)(implicit
    actorSystem: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext,
    timeout: FiniteDuration): Future[RestResult[HttpResponse]] = {
    Http(actorSystem).singleRequest(request).flatMap { response =>
      response.entity.toStrict(timeout).map(_.data.decodeString("utf-8")).map(RestResult(response, _))
    }
  }

  def requestFor[T](httpRequest: HttpRequest)(implicit
    actorSystem: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext,
    timeout: FiniteDuration,
    reads: Reads[T], classTag: ClassTag[T]): Future[RestResult[T]] = {
    request(httpRequest).map(read[T])
  }

  def read[T](result: RestResult[HttpResponse])(implicit reads: Reads[T], classTag: ClassTag[T]): RestResult[T] = {
    RestResult(() => result.entityJson.as[T], result.originalResponse, result.entityString)
  }
}
