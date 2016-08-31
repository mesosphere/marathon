package mesosphere.marathon.api.v2

import java.net.URI
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.{ConflictingChangeException, MarathonConf}
import mesosphere.marathon.api.{AuthResource, MarathonMediaType, RestResource}
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{Network, PodDef}
import spray.json._

@Path("v2/pods")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class PodsResource @Inject() (
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer)(
    implicit
    val clock: Clock,
    val eventBus: EventStream) extends RestResource with AuthResource {

  import PodsResource._

  private[this] val podDefaults = Config.from(config)

  /**
    * HEAD is used to determine whether some Marathon variant supports pods.
    *
    * Performs basic authentication checks, but none for authorization: there's
    * no sensitive data being returned here anyway.
    *
    * @return HTTP OK if pods are supported
    */
  @HEAD
  @Timed
  def capability(@Context req: HttpServletRequest): Response = authenticated(req) { _ =>
    ok()
  }

  // TODO(jdef) force parameter? probably since this supports deployment; but what does it actually
  // mean to "force" a create?
  @POST
  @Timed
  def create(
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(unmarshalJson(decodeBytes(body, req))) { podDef =>

      val pod = PodDefinition(withDefaults(podDef, podDefaults)).withCanonizedIds()

      withAuthorization(CreateRunSpec, pod) {

        def createOrThrow(opt: Option[PodDefinition]) = opt
          .map(_ => throw ConflictingChangeException(s"A pod with id [${pod.id}] already exists."))
          .getOrElse(pod)

        // TODO(jdef) once pods are integrated into groups
        // val plan = result(groupManager.updatePod(app.id, createOrThrow, pod.version, force))

        // TODO(jdef) get the deployment plan ID and URI, stuff them in headers, and echo the pod back to the client
        // maybeDeployments = Some(Seq(Identifiable(plan.id)))

        Events.maybePost(PodCreatedEvent(req.getRemoteAddr, req.getRequestURI))

        Response
          .created(new URI(pod.id.toString))
          .entity(marshalJson(pod.asPodDef))
          .build()
      }
    }(createPodValidator)
  }
}

object PodsResource {

  import mesosphere.marathon.api.v2.validation.PodsValidation

  val createPodValidator: Validator[PodDef] = validator[PodDef] { pod =>
    pod is valid(PodsValidation.podDefValidator)
    pod.version is empty
  }

  case class Config(defaultNetworkName: Option[String])

  object Config {
    def from(conf: MarathonConf): Config =
      new Config(defaultNetworkName = conf.defaultNetworkName.get)
  }

  def withDefaults(podDef: PodDef, config: Config): PodDef =
    // TODO(jdef) defaults for scaling and scheduling policy should come from RAML (and codegen)
    podDef.copy(
      networks = podDef.networks.map { network: Network =>
        config.defaultNetworkName.collect {
          case (defaultName: String) if defaultName.nonEmpty && network.name.isEmpty =>
            network.copy(name = Some(defaultName))
        }.getOrElse(network)
      }
    )

  def decodeBytes(data: Array[Byte], req: HttpServletRequest): String = {
    val maybeEncoding = Option(req.getCharacterEncoding())
    val charset = maybeEncoding match {
      case Some(charsetName) =>
        java.nio.charset.Charset.forName(charsetName)
      case None =>
        StandardCharsets.UTF_8 // TODO(jdef) should this be configurable somewhere?
    }
    new String(data, charset)
  }

  def unmarshalJson(data: String): PodDef =
    data.parseJson.convertTo[PodDef]

  def marshalJson(p: PodDef): String =
    p.toJson.prettyPrint
}
