package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ Directive0, Directive1, RejectionError, Directives => AkkaDirectives }
import com.wix.accord.{ Validator, Result => ValidationResult }
import com.wix.accord
import com.wix.accord.dsl._
import mesosphere.marathon.api.akkahttp.Rejections.Message
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.{ Authorizer, Identity }
import mesosphere.marathon.state.PathId

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

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

  def accepts(mediaType: MediaType): Directive0 = {
    extractRequest.flatMap { request =>
      request.header[Accept] match {
        case Some(accept) if accept.mediaRanges.exists(range => range.matches(mediaType)) => pass
        case _ => reject
      }
    }
  }

  def acceptsAnything: Directive0 = {
    extractRequest.flatMap { request =>
      request.header[Accept] match {
        case None => pass
        case _ => reject
      }
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
  def assumeValid(result: ValidationResult): Directive0 = {
    import mesosphere.marathon.api.akkahttp.EntityMarshallers._
    result match {
      case failure: accord.Failure => reject(ValidationFailed(failure))
      case accord.Success => pass
    }
  }

  def validateInstanceId(possibleId: String): ValidationResult = {
    val validate: Validator[String] = validator[String] { id =>
      id should matchRegexFully(Instance.Id.InstanceIdRegex)
    }
    validate(possibleId)
  }

  def validatePathId(possibleId: String): ValidationResult = PathId.pathIdValidator(PathId(possibleId))

  def withValidatedPathId(possibleId: String): Directive1[PathId] = {
    assumeValid(validatePathId(possibleId)).tflatMap { Unit =>
      provide(PathId(possibleId))
    }
  }

  def normalized[T](value: T, normalizer: Normalization[T]): Directive1[T] = {
    try {
      provide(normalizer.normalized(value))
    } catch {
      case e: NormalizationException => complete((StatusCodes.UnprocessableEntity, e.msg))
    }
  }

  /**
    * Directive for handling "legacy" method calls (ones which might throw an exception)
    *
    * "Unwraps" a `Future[T]` and runs the inner route after future
    * completion with the future's value as an extraction of type `T`.
    * If the future fails or called method throws an exception it will be matched against known legacy
    * exceptions and approproate rejection will be generated. In case exception can't be handled it is bubbled up
    * to the nearest ExceptionHandler.
    *
    * @param f
    * @param identity
    * @param authorizer
    * @tparam T
    * @return
    */
  def onSuccessLegacy[T](f: => Future[T])(implicit identity: Identity, authorizer: Authorizer): Directive1[T] = onComplete({
    try { f }
    catch {
      case NonFatal(ex) =>
        Future.failed(ex)
    }
  }).flatMap {
    case Success(t) =>
      provide(t)
    case Failure(ValidationFailedException(_, failure)) =>
      reject(EntityMarshallers.ValidationFailed(failure))
    case Failure(AccessDeniedException(msg)) =>
      reject(AuthDirectives.NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
    case Failure(e: PodNotFoundException) => reject(
      Rejections.EntityNotFound.noPod(e.id)
    )
    case Failure(e: AppNotFoundException) => reject(
      Rejections.EntityNotFound.noApp(e.id)
    )
    case Failure(RejectionError(rejection)) =>
      reject(rejection)
    case Failure(ConflictingChangeException(msg)) =>
      reject(Rejections.ConflictingChange(Message(msg)))
    case Failure(ex) =>
      throw ex
  }

}
