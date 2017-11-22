package mesosphere.marathon
package api.akkahttp

import java.time.Clock

import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Rejection, RejectionError, Route }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, FromMessageUnmarshaller, Unmarshaller }
import akka.util.ByteString
import com.wix.accord.Descriptions.{ Generic, Path }
import com.wix.accord.{ Failure, RuleViolation, Success, Validator }
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.core.plugin.PluginDefinitions
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import play.api.libs.json._

import scala.collection.breakOut

object EntityMarshallers {
  import Directives.complete
  import mesosphere.marathon.api.v2.json.Formats._

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`application/json`)
      .mapWithCharset {
        case (ByteString.empty, _) => throw Unmarshaller.NoContentException
        case (data, charset) => data.decodeString(charset.nioCharset.name)
      }

  private val jsonStringMarshaller =
    Marshaller.stringMarshaller(`application/json`)

  def jsErrorToFailure(error: JsError): Failure = Failure(
    error.errors.flatMap {
      case (path, validationErrors) =>
        validationErrors.map { validationError =>
          RuleViolation(
            validationError.args.mkString(", "),
            validationError.message,
            path = Path(path.toString.split("/").filter(_ != "").map(Generic(_)): _*))
        }
    }(breakOut)
  )

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
          case error: JsError =>
            throw RejectionError(
              ValidationFailed(jsErrorToFailure(error)))
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
  implicit def appDefinitionUnmarshaller(
    implicit
    normalization: Normalization[raml.App],
    validator: Validator[AppDefinition]): FromEntityUnmarshaller[AppDefinition] = {
    validEntityRaml(playJsonUnmarshaller[raml.App]).handleValidationErrors
  }

  implicit val appDefinitionMarshaller: ToEntityMarshaller[raml.App] = playJsonMarshaller[raml.App]

  def appUpdateUnmarshaller(
    appId: PathId, partialUpdate: Boolean)(
    implicit
    appUpdateNormalization: Normalization[raml.AppUpdate],
    appNormalization: Normalization[raml.App]): FromEntityUnmarshaller[raml.AppUpdate] = {
    if (partialUpdate) {
      playJsonUnmarshaller[raml.AppUpdate].map { appUpdate =>
        appUpdateNormalization.normalized(appUpdate.copy(id = Some(appId.toString)))
      }
    } else
      playJsonUnmarshaller[JsObject].map { jsObj =>
        // this is a complete replacement of the app as we know it, so parse and normalize as if we're dealing
        // with a brand new app because the rules are different (for example, many fields are non-optional with brand-new apps).
        // however since this is an update, the user isn't required to specify an ID as part of the definition so we do
        // some hackery here to pass initial JSON parsing.
        val app = (jsObj + ("id" -> JsString(appId.toString))).as[raml.App]
        appNormalization.normalized(app).toRaml[raml.AppUpdate]
      }
  }.handleValidationErrors

  def appUpdatesUnmarshaller(partialUpdate: Boolean)(
    implicit
    appUpdateNormalization: Normalization[raml.AppUpdate],
    appNormalization: Normalization[raml.App]): FromEntityUnmarshaller[Seq[raml.AppUpdate]] = {
    if (partialUpdate)
      playJsonUnmarshaller[Seq[raml.AppUpdate]].map(_.map(appUpdateNormalization.normalized))
    else
      // this is a complete replacement of the app as we know it, so parse and normalize as if we're dealing
      // with a brand new app because the rules are different (for example, many fields are non-optional with brand-new apps).
      // the version is thrown away in toUpdate so just pass `zero` for now.
      playJsonUnmarshaller[Seq[raml.App]].map(_.map(app => appNormalization.normalized(app).toRaml[raml.AppUpdate]))
  }

  implicit val jsValueMarshaller = playJsonMarshaller[JsValue]
  implicit val wixResultMarshaller = playJsonMarshaller[com.wix.accord.Failure](Validation.failureWrites)
  implicit val rejectionMessageMarshaller = playJsonMarshaller[Rejections.Message]
  implicit val appInfoMarshaller = playJsonMarshaller[AppInfo]
  implicit val podDefMarshaller = playJsonMarshaller[raml.Pod]
  implicit val podDefSeqMarshaller = playJsonMarshaller[Seq[raml.Pod]]
  implicit val podDefUnmarshaller = playJsonUnmarshaller[raml.Pod]
  implicit val podStatus = playJsonMarshaller[raml.PodStatus]
  implicit val podStatuses = playJsonMarshaller[Seq[raml.PodStatus]]
  implicit val leaderInfoMarshaller = playJsonMarshaller[raml.LeaderInfo]
  implicit val messageMarshaller = playJsonMarshaller[raml.Message]
  implicit val infoMarshaller = playJsonMarshaller[raml.MarathonInfo]
  implicit val infoUnmarshaller = playJsonUnmarshaller[raml.MarathonInfo]
  implicit val metricsMarshaller = playJsonMarshaller[raml.Metrics]
  implicit val loggerChangeMarshaller = playJsonMarshaller[raml.LoggerChange]
  implicit val loggerChangeUnmarshaller = playJsonUnmarshaller[raml.LoggerChange]
  implicit val stringMapMarshaller = playJsonMarshaller[Map[String, String]]
  implicit val pluginDefinitionsMarshaller = playJsonMarshaller[PluginDefinitions]
  implicit val queueMarashaller = internalToRamlJsonMarshaller[(Seq[QueuedInstanceInfoWithStatistics], Boolean, Clock), raml.Queue]
  implicit val deploymentResultMarshaller = playJsonMarshaller[raml.DeploymentResult]
  implicit val enrichedTaskMarshaller = playJsonMarshaller[raml.EnrichedTask]
  implicit val enrichedTasksListMarshaller = playJsonMarshaller[raml.EnrichedTasksList]
  implicit val instanceListMarshaller = playJsonMarshaller[raml.InstanceList]
  implicit val singleInstanceMarshaller = playJsonMarshaller[raml.SingleInstance]
  implicit val deleteTasksUnmarshaller = playJsonUnmarshaller[raml.DeleteTasks]
  implicit val seqDateTimeMarshaller = playJsonMarshaller[Seq[Timestamp]]
  implicit val groupUpdateUnmarshaller = playJsonUnmarshaller[raml.GroupUpdate]
  implicit val verisonListMarshaller = playJsonMarshaller[raml.VersionList]

  implicit class FromEntityUnmarshallerOps[T](val um: FromEntityUnmarshaller[T]) extends AnyVal {
    def handleValidationErrors: FromEntityUnmarshaller[T] = um.recover(_ ⇒ _ ⇒ {
      case ValidationFailedException(_, failure) =>
        throw RejectionError(ValidationFailed(failure))
    })
  }

  private def validEntityRaml[A, B](um: FromEntityUnmarshaller[A])(
    implicit
    normalization: Normalization[A],
    reader: raml.Reads[A, B],
    validator: Validator[B]): FromEntityUnmarshaller[B] = {
    um.map { ent =>
      try {
        // Note: Normalization also validates which can throw an exception
        val normalized = reader.read(normalization.normalized(ent))
        validator(normalized) match {
          case Success => normalized
          case failure: Failure =>
            throw RejectionError(ValidationFailed(failure))
        }
      } catch {
        case ValidationFailedException(_, failure) =>
          throw RejectionError(ValidationFailed(failure))
      }
    }
  }

  case class ValidationFailed(failure: Failure) extends Rejection

  def handleNonValid: PartialFunction[Rejection, Route] = {
    case ValidationFailed(failure) =>
      complete(StatusCodes.UnprocessableEntity -> failure)
  }

  implicit def entityUnmarshallerToMessageUnmarshaller[T](um: FromEntityUnmarshaller[T]): FromMessageUnmarshaller[T] =
    Unmarshaller.withMaterializer { implicit ec ⇒ implicit mat ⇒ request ⇒ um(request.entity) }
}
