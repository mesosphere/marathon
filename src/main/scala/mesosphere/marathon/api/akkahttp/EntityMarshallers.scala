package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Rejection, RejectionError, Route }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.util.ByteString
import com.wix.accord.{ Failure, RuleViolation, Success, Validator }
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.raml.{ LoggerChange, Metrics }
import mesosphere.marathon.core.plugin.PluginDefinitions
import mesosphere.marathon.state.AppDefinition
import play.api.libs.json._

object EntityMarshallers {
  import Directives.complete
  import mesosphere.marathon.api.v2.json.Formats._
  import mesosphere.marathon.raml.MetricsConversion._

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`application/json`)
      .mapWithCharset {
        case (ByteString.empty, _) => throw Unmarshaller.NoContentException
        case (data, charset) => data.decodeString(charset.nioCharset.name)
      }

  private val jsonStringMarshaller =
    Marshaller.stringMarshaller(`application/json`)

  /**
    * HTTP entity => `A`
    *
    * @param reads reader for `A`
    * @tparam A type to decode
    * @return unmarshaller for `A`
    */
  private def playJsonUnmarshaller[A](
    implicit
    reads: Reads[A]
  ): FromEntityUnmarshaller[A] = {
    def read(json: JsValue) =
      reads
        .reads(json)
        .recoverTotal {
          case JsError(errors) =>
            val violations = errors.flatMap {
              case (path, validationErrors) =>
                validationErrors.map { validationError =>
                  RuleViolation(
                    validationError.args.mkString(", "),
                    validationError.message,
                    Some(path.toString()))
                }
            }
            throw RejectionError(ValidationFailed(Failure(violations.toSet)))
        }
    jsonStringUnmarshaller.map(data => read(Json.parse(data)))
  }

  /**
    * `A` => HTTP entity
    *
    * @param writes writer for `A`
    * @param printer pretty printer function
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  private def playJsonMarshaller[A](
    implicit
    writes: Writes[A],
    printer: JsValue => String = Json.prettyPrint
  ): ToEntityMarshaller[A] =
    jsonStringMarshaller.compose(printer).compose(writes.writes)

  /**
    * `Internal` => `Raml` => HTTP entity
    * @param ramlWrites converter that translates between the internal and the raml type.
    * @param writes the play json formatter, that converts the raml type into a json object
    * @param printer the printer, that converts a json object into a string
    * @tparam Internal the internal type
    * @tparam Raml the raml type
    * @return a ToEntityMarshaller for the Internal type
    */
  private def internalToRamlJsonMarshaller[Internal, Raml](
    implicit
    ramlWrites: raml.Writes[Internal, Raml],
    writes: Writes[Raml],
    printer: JsValue => String = Json.prettyPrint
  ): ToEntityMarshaller[Internal] =
    jsonStringMarshaller.compose(printer).compose(internal => writes.writes(ramlWrites.write(internal)))

  import mesosphere.marathon.raml.AppConversion.appRamlReader
  implicit def appUnmarshaller(
    implicit
    normalization: Normalization[raml.App], validator: Validator[AppDefinition]) = {
    validEntityRaml(playJsonUnmarshaller[raml.App])
  }

  implicit val jsValueMarshaller = playJsonMarshaller[JsValue]
  implicit val wixResultMarshaller = playJsonMarshaller[com.wix.accord.Failure](Validation.failureWrites)
  implicit val messageMarshaller = playJsonMarshaller[Rejections.Message]
  implicit val appInfoMarshaller = playJsonMarshaller[AppInfo]
  implicit val metricsMarshaller = internalToRamlJsonMarshaller[TickMetricSnapshot, Metrics]
  implicit val loggerChangeMarshaller = playJsonMarshaller[LoggerChange]
  implicit val loggerChangeUnmarshaller = playJsonUnmarshaller[LoggerChange]
  implicit val stringMapMarshaller = playJsonMarshaller[Map[String, String]]
  implicit val pluginDefinitionsMarshaller = playJsonMarshaller[PluginDefinitions]

  private def validEntityRaml[A, B](um: FromEntityUnmarshaller[A])(
    implicit
    normalization: Normalization[A], reader: raml.Reads[A, B], validator: Validator[B]): FromEntityUnmarshaller[B] = {
    um.map { ent =>
      try {
        // Note: Normalization also validates which can throw an exception
        val normalized = reader.read(normalization.normalized(ent))
        validator(normalized) match {
          case Success => normalized
          case failure: Failure =>
            throw new RejectionError(ValidationFailed(failure))
        }
      } catch {
        case ValidationFailedException(_, failure) =>
          throw new RejectionError(ValidationFailed(failure))
      }
    }
  }

  case class ValidationFailed(failure: Failure) extends Rejection

  def handleNonValid: PartialFunction[Rejection, Route] = {
    case ValidationFailed(failure) =>
      complete(StatusCodes.UnprocessableEntity -> failure)
  }
}
