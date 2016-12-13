package mesosphere.marathon
package integration.setup

import play.api.libs.json.{ JsValue, Json }
import spray.http.HttpResponse

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Awaitable }

/**
  * Result of an REST operation.
  */
case class RestResult[+T](valueGetter: () => T, originalResponse: HttpResponse) {
  def code: Int = originalResponse.status.intValue
  def success: Boolean = code == 200
  lazy val value: T = valueGetter()

  /** Transform the value of this result. */
  def map[R](change: T => R): RestResult[R] = {
    RestResult(() => change(valueGetter()), originalResponse)
  }

  /** Display the original response entity (=body) as string. */
  lazy val entityString: String = originalResponse.entity.asString

  /** Parse the original response entity (=body) as json. */
  lazy val entityJson: JsValue = Json.parse(entityString)

  /** Pretty print the original response entity (=body) as json. */
  lazy val entityPrettyJsonString: String = Json.prettyPrint(entityJson)
}

object RestResult {
  def apply(response: HttpResponse): RestResult[HttpResponse] = {
    new RestResult[HttpResponse](() => response, response)
  }

  def await(responseFuture: Awaitable[HttpResponse], waitTime: Duration): RestResult[HttpResponse] = {
    apply(Await.result(responseFuture, waitTime))
  }
}
