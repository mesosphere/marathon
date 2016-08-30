package mesosphere.marathon.api.v2

import java.net.URI
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.PodDef
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

    //TODO(jdef) define a validator for PodDef
    withValid(new String(body, StandardCharsets.UTF_8).parseJson.convertTo[PodDef]) { podDef =>

      val pod = PodDefinition(podDef.copy(
        networks = podDef.networks.map { network =>
          config.defaultNetworkName.get.collect {
            case (defaultName: String) if defaultName.nonEmpty && network.name.isEmpty =>
              network.copy(name = Some(defaultName))
          }.getOrElse(network)
        }
      )).withCanonizedIds()

      checkAuthorization(CreateRunSpec, pod)

      //def createOrThrow(opt: Option[PodDefinition]) = opt
      //  .map(_ => throw ConflictingChangeException(s"A pod with id [${pod.id}] already exists."))
      //  .getOrElse(pod)

      // TODO(jdef) once pods are integrated into groups
      // val plan = result(groupManager.updateApp(app.id, createOrThrow, pod.version, force))

      // TODO(jdef) get the deployment plan ID and URI, stuff them in headers, and echo the pod back to the client
      //val appWithDeployments = AppInfo(
      //  app,
      //  maybeCounts = Some(TaskCounts.zero),
      //  maybeTasks = Some(Seq.empty),
      //  maybeDeployments = Some(Seq(Identifiable(plan.id)))
      //)

      Events.maybePost(PodCreatedEvent(req.getRemoteAddr, req.getRequestURI, pod))

      Response
        .created(new URI(pod.id.toString))
        .entity(pod.asPodDef.toJson.prettyPrint)
        .build()
    }
  }
}
