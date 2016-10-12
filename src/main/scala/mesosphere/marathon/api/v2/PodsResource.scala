package mesosphere.marathon.api.v2

import java.net.URI
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.codahale.metrics.annotation.Timed
import com.wix.accord.Validator
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType, RestResource, TaskKiller }
import mesosphere.marathon.core.appinfo.{ PodSelector, PodStatusService, Selector }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{ Pod, Raml }
import mesosphere.marathon.state.{ PathId, Timestamp }
import play.api.libs.json.Json

@Path("v2/pods")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class PodsResource @Inject() (
    val config: MarathonConf)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    taskKiller: TaskKiller,
    podSystem: PodManager,
    podStatusService: PodStatusService,
    eventBus: EventStream,
    mat: Materializer) extends RestResource with AuthResource {

  import PodsResource._
  implicit val podDefValidator = PodsValidation.podDefValidator(config.availableFeatures)

  // If we change/add/upgrade the notion of a Pod and can't do it purely in the internal model,
  // update the json first
  private def normalize(pod: Pod): Pod = {
    if (pod.networks.exists(_.name.isEmpty)) {
      val networks = pod.networks.map { network =>
        if (network.name.isEmpty) {
          config.defaultNetworkName.get.fold(network) { name =>
            network.copy(name = Some(name))
          }
        } else {
          network
        }
      }
      pod.copy(networks = networks)
    } else {
      pod
    }
  }

  // If we can normalize using the internal model, do that instead.
  private def normalize(pod: PodDefinition): PodDefinition = identity(pod)

  private def marshal(pod: Pod): String = Json.stringify(Json.toJson(pod))

  private def marshal(pod: PodDefinition): String = marshal(Raml.toRaml(pod))

  private def unmarshal(bytes: Array[Byte]): Pod = {
    normalize(Json.parse(bytes).as[Pod])
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
  @Timed
  def capability(@Context req: HttpServletRequest): Response = authenticated(req) { _ =>
    ok()
  }

  @POST @Timed
  def create(
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = {
    authenticated(req) { implicit identity =>
      withValid(unmarshal(body)) { podDef =>
        val pod = normalize(Raml.fromRaml(normalize(podDef)))
        withAuthorization(CreateRunSpec, pod) {
          val deployment = result(podSystem.create(pod, force))
          Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Created))

          Response.created(new URI(pod.id.toString))
            .header(DeploymentHeader, deployment.id)
            .entity(marshal(pod))
            .build()
        }
      }
    }
  }

  @PUT @Timed @Path("""{id:.+}""")
  def update(
    @PathParam("id") id: String,
    body: Array[Byte],
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    import PathId._

    val podId = id.toRootPath
    withValid(unmarshal(body)) { podDef =>
      if (podId != podDef.id.toRootPath) {
        Response.status(Status.BAD_REQUEST).entity(
          s"""
            |{"message": "'$podId' does not match definition's id ('${podDef.id}')" }
          """.stripMargin
        ).build()
      } else {
        val pod = normalize(Raml.fromRaml(normalize(podDef)))
        withAuthorization(UpdateRunSpec, pod) {
          val deployment = result(podSystem.update(pod, force))
          Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Updated))

          val builder = Response
            .ok(new URI(pod.id.toString))
            .entity(marshal(pod))
            .header(DeploymentHeader, deployment.id)
          builder.build()
        }
      }
    }
  }

  @GET @Timed
  def findAll(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val pods = result(podSystem.findAll(isAuthorized(ViewRunSpec, _)).runWith(Sink.seq))
    ok(Json.stringify(Json.toJson(pods.map(Raml.toRaml(_)))))
  }

  @GET @Timed @Path("""{id:.+}""")
  def find(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    import PathId._

    withValid(id.toRootPath) { id =>
      result(podSystem.find(id)).fold(notFound(s"""{"message": "pod with $id does not exist"}""")) { pod =>
        withAuthorization(ViewRunSpec, pod) {
          ok(marshal(pod))
        }
      }
    }
  }

  @DELETE @Timed @Path("""{id:.+}""")
  def remove(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    import PathId._

    withValid(id.toRootPath) { id =>
      withAuthorization(DeleteRunSpec, result(podSystem.find(id)), unknownPod(id)) { pod =>

        val deployment = result(podSystem.delete(id, force))

        Events.maybePost(PodEvent(req.getRemoteAddr, req.getRequestURI, PodEvent.Deleted))
        Response.status(Status.ACCEPTED)
          .location(new URI(deployment.id)) // TODO(jdef) probably want a different header here since deployment != pod
          .header(DeploymentHeader, deployment.id)
          .build()
      }
    }
  }

  @GET
  @Timed
  @Path("""{id:.+}::status""")
  def status(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>

    import PathId._

    withValid(id.toRootPath) { id =>
      val maybeStatus = podStatusService.selectPodStatus(id, authzSelector)
      result(maybeStatus).fold(notFound(id)) { status =>
        ok(Json.stringify(Json.toJson(status)))
      }
    }
  }

  @GET
  @Timed
  @Path("""{id:.+}::versions""")
  def versions(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    import PathId._
    import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
    withValid(id.toRootPath) { id =>
      result(podSystem.find(id)).fold(notFound(id)) { pod =>
        withAuthorization(ViewRunSpec, pod) {
          val versions = podSystem.versions(id).runWith(Sink.seq)
          ok(Json.stringify(Json.toJson(result(versions))))
        }
      }
    }
  }

  @GET
  @Timed
  @Path("""{id:.+}::versions/{version}""")
  def version(@PathParam("id") id: String, @PathParam("version") versionString: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    import PathId._
    val version = Timestamp(versionString)
    withValid(id.toRootPath) { id =>
      result(podSystem.version(id, version)).fold(notFound(id)) { pod =>
        withAuthorization(ViewRunSpec, pod) {
          ok(marshal(pod))
        }
      }
    }
  }

  @GET
  @Timed
  @Path("::status")
  @SuppressWarnings(Array("OptionGet", "FilterOptionAndGet"))
  def allStatus(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val future = podSystem.ids().mapAsync(Int.MaxValue) { id =>
      podStatusService.selectPodStatus(id, authzSelector)
    }.filter(_.isDefined).map(_.get).runWith(Sink.seq)

    ok(Json.stringify(Json.toJson(result(future))))
  }

  @DELETE
  @Timed
  @Path("""{id:.+}::instances/{instanceId}""")
  def killInstance(
    @PathParam("id") id: String,
    @PathParam("instanceId") instanceId: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    import PathId._
    import com.wix.accord.dsl._

    implicit val validId: Validator[String] = validator[String] { ids =>
      ids should matchRegexFully(Instance.Id.InstanceIdRegex)
    }
    // don't need to authorize as taskKiller will do so.
    withValid(id.toRootPath) { id =>
      withValid(instanceId) { instanceId =>
        val instances = result(taskKiller.kill(id, _.find(_.instanceId == Instance.Id(instanceId))))
        instances.headOption.fold(unknownTask(instanceId))(instance => ok(jsonString(instance)))
      }
    }
  }

  @DELETE
  @Timed
  @Path("""{id:.+}::instances""")
  def killInstances(@PathParam("id") id: String, body: Array[Byte], @Context req: HttpServletRequest): Response =
    authenticated(req) { implicit identity =>
      import PathId._
      import Validation._
      import com.wix.accord.dsl._

      implicit val validIds: Validator[Set[String]] = validator[Set[String]] { ids =>
        ids is every(matchRegexFully(Instance.Id.InstanceIdRegex))
      }

      // don't need to authorize as taskKiller will do so.
      withValid(id.toRootPath) { id =>
        withValid(Json.parse(body).as[Set[String]]) { instancesToKill =>
          val instancesDesired = instancesToKill.map(Instance.Id(_))
          def toKill(instances: Iterable[Instance]): Iterable[Instance] = {
            instances.filter(instance => instancesDesired.contains(instance.instanceId))
          }
          val instances = result(taskKiller.kill(id, toKill))
          ok(Json.toJson(instances))
        }
      }
    }

  private def notFound(id: PathId): Response = notFound(s"""{"message": "pod '$id' does not exist"}""")
}

object PodsResource {
  val DeploymentHeader = "Marathon-Deployment-Id"

  def authzSelector(implicit authz: Authorizer, identity: Identity): PodSelector = Selector[PodDefinition] { pod =>
    authz.isAuthorized(identity, ViewRunSpec, pod)
  }
}
