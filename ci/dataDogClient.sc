#!/usr/bin/env amm

import java.time.Instant
import scalaj.http._
import upickle._

/**
 * Makes a POST request to DataDog's API with provided path and body.
 *
 * @param path The API path, e.g. 'series'.
 * @param body The body of the post request.
 */
def execute(path:String, body: String): Unit = {

  val DATADOG_API_KEY = sys.env.getOrElse(
    "DATADOG_API_KEY",
    throw new IllegalArgumentException("DATADOG_API_KEY enviroment variable was not set.")
  )

  val response = Http(s"https://api.datadoghq.com/api/v1/$path")
    .header("Content-type", "application/json")
    .param("api_key", DATADOG_API_KEY)
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .postData(body)
    .asString
    .throwError
}

/**
 * Report a metric count to DataDog.
 *
 * @param name The name of the metric, e.g. "marathon.build.loop.master.successes".
 * @param count The count of the metric.
 */
@main
def reportCount(name: String, count: Int): Unit = {

  val now = Instant.now.getEpochSecond
  val metric = Js.Obj(
    "metric" -> Js.Str(name),
    "points" -> Js.Arr(Js.Arr(Js.Num(now), Js.Num(count))),
    "type" -> Js.Str("count")
    )
  val request = Js.Obj("series"  -> Js.Arr(metric))
  execute("series", request.toString)
}
