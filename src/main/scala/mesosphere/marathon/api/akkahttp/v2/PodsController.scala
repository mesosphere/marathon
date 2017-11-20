package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.event.EventStream
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directive1, PathMatchers, RejectionError, Route }
import mesosphere.marathon.api.akkahttp._
import mesosphere.marathon.api.akkahttp.PathMatchers.{ PodsPathIdLike, forceParameter }
import mesosphere.marathon.api.akkahttp.Rejections.Message
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.api.v2.PodNormalization
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.PodEvent
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.raml.{ PodConversion, Raml }

import async.Async._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

class PodsController(
    val config: MarathonConf,
    val electionService: ElectionService,
    val podManager: PodManager,
    val groupManager: GroupManager,
    val pluginManager: PluginManager,
    val eventBus: EventStream,
    val clock: Clock)(
    implicit
    val authorizer: Authorizer,
    val authenticator: Authenticator,
    val executionContext: ExecutionContext) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  val podNormalizer = PodNormalization.apply(PodNormalization.Configuration(
    config.defaultNetworkName.get))

  def capability(): Route =
    authenticated.apply { implicit identity =>
      complete((StatusCodes.OK, ""))
    }

  @SuppressWarnings(Array("all")) // async/await
  def create(): Route =
    authenticated.apply { implicit identity =>
      (extractClientIP & forceParameter) { (clientIp, force) =>
        extractRequest { req =>
          entity(as[raml.Pod]) { podDef =>
            normalized(podDef, podNormalizer) { normalizedPodDef =>
              val normalizedPodDef = podNormalizer.normalized(podDef)
              val pod = Raml.fromRaml(normalizedPodDef).copy(version = clock.now())
              assumeValid(PodsValidation.pluginValidators(pluginManager).apply(pod)) {
                authorized(CreateRunSpec, pod).apply {
                  val p = async {
                    val deployment = await(podManager.create(pod, force))

                    // TODO: How should we get the ip?
                    val ip = clientIp.getAddress().toString
                    eventBus.publish(PodEvent(ip, req.uri.toString(), PodEvent.Created))

                    deployment
                  }
                  onSuccess(p) { plan =>
                    val ramlPod = PodConversion.podRamlWriter.write(pod)
                    val responseHeaders = Seq(
                      Location(Uri(pod.id.toString)),
                      Headers.`Marathon-Deployment-Id`(plan.id)
                    )
                    complete((StatusCodes.Created, responseHeaders, ramlPod))
                  }
                }
              }
            }
          }
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  def update(podId: PathId): Route = {
    authenticated.apply { implicit identity =>
      (entity(as[raml.Pod]) & forceParameter & extractClientIP & extractUri) {
        case (ramlPod, force, host, uri) =>
          normalized(ramlPod, podNormalizer) { normalizedPodDef =>
            val pod = Raml.fromRaml(normalizedPodDef).copy(version = clock.now())
            assumeValid(PodsValidation.pluginValidators(pluginManager).apply(pod)) {
              authorized(UpdateRunSpec, pod).apply {
                val deploymentPlan = async {
                  val plan = await(podManager.update(pod, force))
                  eventBus.publish(PodEvent(host.toString(), uri.toString(), PodEvent.Updated))
                  plan
                }
                onSuccessLegacy(Some(podId))(deploymentPlan).apply { plan =>
                  val ramlPod = PodConversion.podRamlWriter.write(pod)
                  complete((StatusCodes.OK, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), ramlPod))
                }
              }
            }
          }
      }
    }
  }

  def findAll(): Route = ???

  def find(podId: PathId): Route = ???

  def remove(podId: PathId): Route = ???

  def status(podId: PathId): Route = ???

  def versions(podId: PathId): Route = ???

  def version(podId: PathId, v: String): Route = ???

  def allStatus(): Route = ???

  def killInstance(instanceId: Instance.Id): Route = ???

  def killInstances(podId: PathId): Route = ???

  // format: OFF
  override val route: Route =
    asLeader(electionService) {
      head {
        capability()
      } ~
      get {
        pathEnd {
          findAll()
        } ~
        path("::status" ~ PathEnd) {
          allStatus()
        } ~
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            find(id)
          }
        } ~
        path(PodsPathIdLike ~ "::status" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            status(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            versions(id)
          }
        } ~
        path(PodsPathIdLike ~ "::versions" / PathMatchers.Segment) { (runSpecId: String, v: String) =>
          withValidatedPathId(runSpecId) { id =>
            version(id, v)
          }
        }
      } ~
      post {
        pathEndOrSingleSlash {
          create()
        }
      } ~
      delete {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            remove(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            killInstances(id)
          }
        } ~
        path(PodsPathIdLike ~ "::instances" / PathMatchers.Segment) { (runSpecId: String, instanceId: String) =>
          assumeValid(validatePathId(runSpecId) and validateInstanceId(instanceId)) {
            killInstance(Instance.Id(instanceId))
          }
        }
      } ~
      put {
        path(PodsPathIdLike ~ PathEnd) { runSpecId: String =>
          withValidatedPathId(runSpecId) { id =>
            update(id)
          }
        }
      }
    }
  // format: ON

  private def onSuccessLegacy[T](maybePodId: Option[PathId])(f: => Future[T])(implicit identity: Identity): Directive1[T] = onComplete({
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
    case Failure(_: PodNotFoundException) =>
      reject(
        maybePodId.map { appId =>
          Rejections.EntityNotFound.noPod(appId)
        } getOrElse Rejections.EntityNotFound()
      )
    case Failure(RejectionError(rejection)) =>
      reject(rejection)
    case Failure(ConflictingChangeException(msg)) =>
      reject(Rejections.ConflictingChange(Message(msg)))
    case Failure(ex) =>
      throw ex
  }

}
