package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ DateTime, HttpHeader, HttpMethods, HttpProtocols }
import akka.http.scaladsl.server.{ Directive, Directive0, Directive1, Route, Directives => AkkaDirectives }
import com.wix.accord.{ Failure, Success, Validator, Result => ValidationResult }
import com.wix.accord.dsl._
import mesosphere.marathon.core.instance.Instance

import scala.concurrent.duration._

/**
  * All Marathon Directives and Akka Directives
  *
  * These should be imported by the respective controllers
  */
object Directives extends AuthDirectives with LeaderDirectives with AkkaDirectives {

  /**
    * Use this directive to enable Cross Origin Resource Sharing for a given set of origins.
    *
    * @param origins the origins to allow.
    */
  def corsResponse(origins: Seq[String]): Directive0 = {
    import HttpMethods._
    extractRequest.flatMap { request =>
      val headers = Seq.newBuilder[HttpHeader]

      // add all pre-defined origins
      origins.foreach(headers += `Access-Control-Allow-Origin`(_))

      // allow all header that are defined as request headers
      request.header[`Access-Control-Request-Headers`].foreach(request =>
        headers += `Access-Control-Allow-Headers`(request.headers)
      )

      // define allowed methods
      headers += `Access-Control-Allow-Methods`(GET, HEAD, OPTIONS)

      // do not ask again for one day
      headers += `Access-Control-Max-Age`(1.day.toSeconds)

      respondWithHeaders (headers.result())
    }
  }

  /**
    * The noCache directive will set proper no-cache headers based on the HTTP protocol
    */
  val noCache: Directive0 = {
    import CacheDirectives._
    import HttpProtocols._
    extractRequest.flatMap {
      _.protocol match {
        case `HTTP/1.0` =>
          respondWithHeaders (
            RawHeader("Pragma", "no-cache")
          )
        case `HTTP/1.1` =>
          respondWithHeaders (
            `Cache-Control`(`no-cache`, `no-store`, `must-revalidate`),
            `Expires`(DateTime.now)
          )
      }
    }
  }

  /**
    * Rejects the request if the validation result is a failure. Proceeds otherwise.
    * @param result The result of a Wix validation.
    * @return The passed inner route.
    */
  def assumeValid(result: ValidationResult): Directive0 = Directive { f: (Unit => Route) =>
    import mesosphere.marathon.api.akkahttp.EntityMarshallers._
    result match {
      case failure: Failure => reject(ValidationFailed(failure))
      case Success => f(Unit)
    }
  }

  /**
    * Matches the remaining path and transforms it into an instance id or rejects if it is not a valid id.
    */
    class ExtractInstanceId extends Directive[Instance.Id] {
      override def tapply(f: (Instance.Id) => Route): Route =
        extract[Instance.Id] { requestContext =>
          val id = requestContext.unmatchedPath.toString
          val validate: Validator[String] = validator[String] { id =>
            id should matchRegexFully(Instance.Id.InstanceIdRegex)
          }
          def x(): Route = f(Instance.Id(id))
          assumeValid(validate(id)) { x }
        }
    }
    val foo = new ExtractInstanceId
}
