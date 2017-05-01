package mesosphere.marathon
package integration.setup

import akka.http.scaladsl.model.HttpResponse
import play.api.libs.json.{ JsValue, Json }

/**
  * Result of an REST operation.
  */
case class RestResult[+T](valueGetter: () => T, originalResponse: HttpResponse, entityString: String) {
  def code: Int = originalResponse.status.intValue
  def success: Boolean = code >= 200 && code < 300
  lazy val value: T = valueGetter()

  /** Transform the value of this result. */
  def map[R](change: T => R): RestResult[R] = {
    RestResult(() => change(valueGetter()), originalResponse, entityString)
  }

  /** Parse the original response entity (=body) as json. */
  lazy val entityJson: JsValue = Json.parse(entityString)

  /** Pretty print the original response entity (=body) as json. */
  lazy val entityPrettyJsonString: String = Json.prettyPrint(entityJson)
}

object RestResult {
  def apply(response: HttpResponse, entityString: String): RestResult[HttpResponse] = {
    new RestResult[HttpResponse](() => response, response, entityString)
  }
}
