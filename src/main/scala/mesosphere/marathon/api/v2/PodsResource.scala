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
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.api.{AuthResource, MarathonMediaType, RestResource}
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{Network, PodDef, PodStatus}
import spray.json._

@Path("v2/pods")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class PodsResource @Inject() (
    val config: MarathonConf,
    val authenticator: Authenticator,
    val authorizer: Authorizer)(
    implicit
    val podSystem: PodsResource.System,
    val clock: Clock,
    val eventBus: EventStream) extends RestResource with AuthResource {

  import PodsResource._
  import PodsResourceInternal._

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
  @POST @Timed
  def create(
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(unmarshalJson(decodeBytes(body, req))) { podDef =>

      val pod = PodDefinition(withDefaults(podDef, podDefaults)).withCanonizedIds()

      withAuthorization(CreateRunSpec, pod) {
        val (createdPod, deploymentId) = podSystem.create(pod, force)
        Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Created))

        val builder = Response
          .created(new URI(createdPod.id.toString))
          .entity(marshalJson(createdPod.asPodDef))

        deploymentId.foreach(did => builder.header(DEPLOYMENT_ID_HEADER, did))
        builder.build()
      }
    }(createPodValidator)
  }

  @GET @Timed
  def findAll(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    val authSelector = Selector { pod =>
      isAuthorized(ViewRunSpec, pod)
    }
    val pods: Iterable[PodDefinition] = podSystem.findAll(authSelector)
    ok(marshalJson(pods.map(_.asPodDef)))
  }

  @GET @Timed @Path("""{id:.+}""")
  def find(
    @PathParam("id") id:String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(id) { _ =>
      val pod: PodDefinition = podSystem.find(id)
      withAuthorization(ViewRunSpec, pod) {
        ok(marshalJson(pod.asPodDef))
      }
    }(PodsValidation.idValidator)
  }

  @PUT @Timed @Path("""{id:.+}""")
  def update(
    @PathParam("id") id:String,
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(unmarshalJson(decodeBytes(body, req))) { podDef =>

      require(id == podDef.id)
      val pod = PodDefinition(withDefaults(podDef, podDefaults)).withCanonizedIds()

      withAuthorization(UpdateRunSpec, pod) {

        val (updatedPod, deploymentId) = podSystem.update(pod, force)
        Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Updated))

        val builder = Response
          .ok(new URI(updatedPod.id.toString))
          .entity(marshalJson(updatedPod.asPodDef))

        deploymentId.foreach(did => builder.header(DEPLOYMENT_ID_HEADER, did))
        builder.build()
      }
    }(createPodValidator and updatePodValidator)
  }

  @DELETE @Timed @Path("""{id:.+}""")
  def remove(
    @PathParam("id") id:String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(id) { _ =>
      val pod: PodDefinition = podSystem.find(id)
      withAuthorization(DeleteRunSpec, pod) {

        val (deletedPod, deploymentId) = podSystem.delete(id, force)
        Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Deleted))

        val builder = Response
          .ok(new URI(deletedPod.id.toString))
          .entity(marshalJson(deletedPod.asPodDef))

        deploymentId.foreach(did => builder.header(DEPLOYMENT_ID_HEADER, did))
        builder.build()
      }
    }(PodsValidation.idValidator)
  }

  @GET @Timed @Path("""{id:.+}::status""")
  def examine(
    @PathParam("id") id:String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    withValid(id) { _ =>
      val pod: PodDefinition = podSystem.find(id)
      withAuthorization(ViewRunSpec, pod) {
        val status: PodStatus = podSystem.examine(id)
        ok(marshalJson(status))
      }
    }(PodsValidation.idValidator)
  }
}

/**
  * public interfaces, specific to the pods api, that other pieces of the system implement and/or interact with
  */
object PodsResource {

  /**
    * Pod operations that reach this interface have already been authenticated, authorized, and otherwise validated.
    * TODO(jdef) does it really make sense that deployment ID's are returned an Option[String] instead of String?
    */
  trait System {

    /** Create results in the deployment of a new pod
      *
      * @param p     is the new pod to deploy
      * @param force TODO(jdef) not sure what this means for create
      * @return the created pod and an optional, stringified deployment ID
      */
    def create(p: PodDefinition, force: Boolean): (PodDefinition, Option[String])

    def findAll(s: Selector): Iterable[PodDefinition]

    def find(id: String): PodDefinition

    def update(p: PodDefinition, force: Boolean): (PodDefinition, Option[String])

    def delete(id: String, force: Boolean): (PodDefinition, Option[String])

    def examine(id: String): PodStatus
  }

  trait Selector extends Function1[PodDefinition, Boolean]

  object Selector {
    def apply(f: PodDefinition => Boolean): Selector = new Selector {
      override def apply(p: PodDefinition): Boolean = f(p)
    }
  }
}

/**
  * Helpers for internal use (by PodsResource and related tests) only
  */
protected object PodsResourceInternal {

  val DEPLOYMENT_ID_HEADER = "MARATHON-Deployment-ID"

  val createPodValidator: Validator[PodDef] = validator[PodDef] { pod =>
    pod is valid(PodsValidation.podDefValidator)
    pod.version is empty
  }

  val updatePodValidator: Validator[PodDef] = validator[PodDef] { pod =>
    // TODO(jdef) only some fields are mutable, enforce that here
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

  def marshalJson(p: Iterable[PodDef]): String = {
    import DefaultJsonProtocol._
    p.toJson.prettyPrint
  }

  def marshalJson(s: PodStatus): String =
    s.toJson.prettyPrint
}
