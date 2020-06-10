package mesosphere.marathon
package api.v2

import java.net.URI
import java.time.Clock

import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.wix.accord.Validator
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.Normalization._
import mesosphere.marathon.api.RestResource.RestStreamingBody
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.api.{AuthResource, RestResource, TaskKiller}
import mesosphere.marathon.core.appinfo.{PodSelector, PodStatusService, Selector}
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.{PodDefinition, PodManager}
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{Pod, Raml}
import mesosphere.marathon.state.{PathId, Timestamp, VersionInfo}
import mesosphere.marathon.util.RoleSettings
import play.api.libs.json.Json

import scala.async.Async._
import scala.concurrent.ExecutionContext

@Path("v2/pods")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class PodsResource @Inject() (
    val config: MarathonConf,
    clock: Clock,
    taskKiller: TaskKiller,
    podSystem: PodManager,
    podStatusService: PodStatusService,
    groupManager: GroupManager,
    scheduler: MarathonScheduler,
    pluginManager: PluginManager
)(implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    eventBus: EventStream,
    mat: Materializer,
    val executionContext: ExecutionContext
) extends RestResource
    with AuthResource {

  import PodsResource._

  // If we can normalize using the internal model, do that instead.
  // The version of the pod is changed here to make sure, the user has not send a version.
  private def normalize(pod: PodDefinition): PodDefinition = pod.copy(versionInfo = VersionInfo.OnlyVersion(clock.now()))

  private def unmarshal(bytes: Array[Byte]): Pod = {
    // no normalization or validation here, that happens elsewhere and in a precise order
    Json.parse(bytes).as[Pod]
  }

  /**
    * HEAD is used to determine whether some Marathon variant supports pods.
    *
    * Performs basic authentication checks, but none for authorization: there's
    * no sensitive data being returned here anyway.
    *
    * @return HTTP OK if pods are supported
    */
  @HEAD
  def capability(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        ok()
      }
    }

  @POST
  def create(
      body: Array[Byte],
      @DefaultValue("false") @QueryParam("force") force: Boolean,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val podRaml = unmarshal(body)

        val roleSettings = RoleSettings.forService(config, PathId(podRaml.id).canonicalPath(PathId.root), groupManager.rootGroup(), false)
        implicit val normalizer: Normalization[Pod] = PodNormalization(PodNormalization.Configuration(config, roleSettings))
        implicit val podValidator: Validator[Pod] = PodsValidation.podValidator(config, scheduler.mesosMasterVersion())
        implicit val podDefValidator: Validator[PodDefinition] = PodsValidation.podDefValidator(pluginManager, roleSettings)

        validateOrThrow(podRaml)
        val podDef = normalize(Raml.fromRaml(podRaml.normalize))
        validateOrThrow(podDef)(podDefValidator)

        checkAuthorization(CreateRunSpec, podDef)
        val deployment = await(podSystem.create(podDef, force))
        eventBus.publish(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Created))

        Response
          .created(new URI(podDef.id.toString))
          .header(RestResource.DeploymentHeader, deployment.id)
          .entity(new RestStreamingBody[raml.Pod](Raml.toRaml(podDef)))
          .build()
      }
    }

  @PUT
  @Path("""{id:.+}""")
  def update(
      @PathParam("id") id: String,
      body: Array[Byte],
      @DefaultValue("false") @QueryParam("force") force: Boolean,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        import PathId._

        val podId = id.toAbsolutePath
        val podRaml = unmarshal(body)

        val roleSettings = RoleSettings.forService(config, podId, groupManager.rootGroup(), force)
        implicit val normalizer: Normalization[Pod] = PodNormalization(PodNormalization.Configuration(config, roleSettings))
        implicit val podValidator: Validator[Pod] = PodsValidation.podValidator(config, scheduler.mesosMasterVersion())
        implicit val podDefValidator: Validator[PodDefinition] = PodsValidation.podDefValidator(pluginManager, roleSettings)

        validateOrThrow(podRaml)
        if (podId != podRaml.id.toAbsolutePath) {
          Response
            .status(Status.BAD_REQUEST)
            .entity(
              s"""
             |{"message": "'$podId' does not match definition's id ('${podRaml.id}')" }
          """.stripMargin
            )
            .build()
        } else {
          val podDef = normalize(Raml.fromRaml(podRaml.normalize))
          validateOrThrow(podDef)

          checkAuthorization(UpdateRunSpec, podDef)
          val deployment = await(podSystem.update(podDef, force))
          eventBus.publish(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Updated))

          val builder = Response
            .ok(new URI(podDef.id.toString))
            .entity(new RestStreamingBody(Raml.toRaml(podDef)))
            .header(RestResource.DeploymentHeader, deployment.id)
          builder.build()
        }
      }
    }

  @GET
  def findAll(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val pods = podSystem.findAll(isAuthorized(ViewRunSpec, _))
        ok(pods.map(Raml.toRaml(_)))
      }
    }

  @GET
  @Path("""{id:.+}""")
  def find(@PathParam("id") id: String, @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))

        import PathId._

        withValid(id.toAbsolutePath) { id =>
          podSystem.find(id).fold(notFound(s"""{"message": "pod with $id does not exist"}""")) { pod =>
            withAuthorization(ViewRunSpec, pod) {
              ok(Raml.toRaml(pod))
            }
          }
        }
      }
    }

  @DELETE
  @Path("""{id:.+}""")
  def remove(
      @PathParam("id") idOrig: String,
      @DefaultValue("false") @QueryParam("force") force: Boolean,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))

        import PathId._

        val id = idOrig.toAbsolutePath
        validateOrThrow(id)
        podSystem.find(id) match {
          case Some(pod) =>
            checkAuthorization(DeleteRunSpec, pod)
            val deployment = await(podSystem.delete(id, force))

            eventBus.publish(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Deleted))
            Response
              .status(Status.ACCEPTED)
              .location(new URI(deployment.id)) // TODO(jdef) probably want a different header here since deployment != pod
              .header(RestResource.DeploymentHeader, deployment.id)
              .build()
          case None =>
            unknownPod(id)
        }
      }
    }

  @GET
  @Path("""{id:.+}::status""")
  def status(@PathParam("id") id: String, @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))

        import PathId._

        await(withValidF(id.toAbsolutePath) { id =>
          podStatusService.selectPodStatus(id, authzSelector).map {
            case None => notFound(id)
            case Some(status) => ok(Json.stringify(Json.toJson(status)))
          }
        })
      }
    }

  @GET
  @Path("""{id:.+}::versions""")
  def versions(@PathParam("id") id: String, @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        import PathId._
        import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
        await(withValidF(id.toAbsolutePath) { id =>
          async {
            val versions = await(podSystem.versions(id).runWith(Sink.seq))
            podSystem.find(id).fold(notFound(id)) { pod =>
              withAuthorization(ViewRunSpec, pod) {
                ok(Json.stringify(Json.toJson(versions)))
              }
            }
          }
        })
      }
    }

  @GET
  @Path("""{id:.+}::versions/{version}""")
  def version(
      @PathParam("id") id: String,
      @PathParam("version") versionString: String,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        import PathId._
        val version = Timestamp(versionString)
        await(withValidF(id.toAbsolutePath) { id =>
          async {
            await(podSystem.version(id, version)).fold(notFound(id)) { pod =>
              withAuthorization(ViewRunSpec, pod) {
                ok(Raml.toRaml(pod))
              }
            }
          }
        })
      }
    }

  @GET
  @Path("::status")
  def allStatus(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val ids = podSystem.ids()
        val future = podStatusService.selectPodStatuses(ids, authzSelector)
        ok(Json.stringify(Json.toJson(await(future))))
      }
    }

  @DELETE
  @Path("""{id:.+}::instances/{instanceId}""")
  def killInstance(
      @PathParam("id") idOrig: String,
      @PathParam("instanceId") instanceId: String,
      @DefaultValue("false") @QueryParam("wipe") wipe: Boolean,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        import PathId._
        import com.wix.accord.dsl._

        implicit val validId: Validator[String] = validator[String] { ids =>
          ids should matchRegexFully(Instance.Id.InstanceIdRegex)
        }
        // don't need to authorize as taskKiller will do so.
        val id = idOrig.toAbsolutePath
        validateOrThrow(id)
        validateOrThrow(instanceId)
        val parsedInstanceId = Instance.Id.fromIdString(instanceId)
        val instances = await(taskKiller.kill(id, _.filter(_.instanceId == parsedInstanceId), wipe))

        instances.headOption match {
          case None => (unknownTask(instanceId))
          case Some(instance) =>
            val raml = Raml.toRaml(instance)
            ok(Json.stringify(Json.toJson(raml)))
        }
      }
    }

  @DELETE
  @Path("""{id:.+}::instances""")
  def killInstances(
      @PathParam("id") idOrig: String,
      @DefaultValue("false") @QueryParam("wipe") wipe: Boolean,
      body: Array[Byte],
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        import PathId._
        import Validation._
        import com.wix.accord.dsl._

        implicit val validIds: Validator[Set[String]] = validator[Set[String]] { ids =>
          ids is every(matchRegexFully(Instance.Id.InstanceIdRegex))
        }

        // don't need to authorize as taskKiller will do so.
        val id = idOrig.toAbsolutePath
        validateOrThrow(id)
        val instancesToKill = Json.parse(body).as[Set[String]]
        validateOrThrow(instancesToKill)
        val instancesDesired = instancesToKill.map(Instance.Id.fromIdString(_))

        def toKill(instances: Seq[Instance]): Seq[Instance] = {
          instances.filter(instance => instancesDesired.contains(instance.instanceId))
        }

        val instances = await(taskKiller.kill(id, toKill, wipe)).map { instance => Raml.toRaml(instance) }
        ok(instances)
      }
    }

  private def notFound(id: PathId): Response = unknownPod(id)
}

object PodsResource {
  def authzSelector(implicit authz: Authorizer, identity: Identity): PodSelector =
    Selector[PodDefinition] { pod =>
      authz.isAuthorized(identity, ViewRunSpec, pod)
    }
}
