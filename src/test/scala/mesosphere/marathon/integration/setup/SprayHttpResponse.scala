package mesosphere.marathon.integration.setup

import play.api.libs.json.{ JsError, JsSuccess, Json, Reads }
import spray.http.HttpResponse
import spray.httpx.PlayJsonSupport

import scala.reflect.ClassTag

object SprayHttpResponse {
  def read[T](implicit reads: Reads[T], classTag: ClassTag[T]): HttpResponse => RestResult[T] = responseResult.andThen { result =>
    result.map(_ => Json.fromJson(result.entityJson)).map {
      case JsSuccess(value, _) => value
      case JsError(errors) =>
        throw new IllegalArgumentException(
          s"could not parse as $classTag:\n${Json.prettyPrint(result.entityJson)}\nErrors:\n${errors.mkString("\n")}")
    }
  }
  def responseResult: HttpResponse => RestResult[HttpResponse] = response => RestResult(response)
}
