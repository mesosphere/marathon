package mesosphere.marathon
package api.akkahttp.v2

import java.time.Clock

import akka.event.EventStream
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.akkahttp.{ Controller, Headers, Rejections }
import mesosphere.marathon.api.akkahttp.PathMatchers.{ PodsPathIdLike, forceParameter }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, CreateRunSpec, DeleteRunSpec, ViewRunSpec }
import mesosphere.marathon.state.PathId
import akka.http.scaladsl.server.PathMatchers
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import com.wix.accord.Validator
import mesosphere.marathon.api.v2.PodNormalization
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.event.PodEvent
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.{ PodConversion, PodStatusConversion, Raml }
import mesosphere.marathon.util.SemanticVersion

import async.Async._
import scala.concurrent.{ ExecutionContext, Future }

class PodsController(
    val config: MarathonConf,
    val electionService: ElectionService,
    val podManager: PodManager,
    val podStatusService: PodStatusService,
    val groupManager: GroupManager,
    val pluginManager: PluginManager,
    val eventBus: EventStream,
    val scheduler: MarathonScheduler,
    val clock: Clock)(
    implicit
    val authorizer: Authorizer,
    val authenticator: Authenticator,
    val mat: Materializer,
    val executionContext: ExecutionContext) extends Controller {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  val podNormalizer = PodNormalization.apply(PodNormalization.Configuration(
    config.defaultNetworkName.get))

  def podDefValidator(): Validator[raml.Pod] =
    PodsValidation.podValidator(
      config.availableFeatures,
      scheduler.mesosMasterVersion().getOrElse(SemanticVersion(0, 0, 0)), config.defaultNetworkName.get)

  def capability(): Route =
    authenticated.apply { implicit identity =>
      complete((StatusCodes.OK, ""))
    }

  @SuppressWarnings(Array("all")) // async/await
  def create(): Route =
    authenticated.apply { implicit identity =>
      (extractClientIP & forceParameter & extractUri) { (clientIp, force, uri) =>
        entity(as[raml.Pod]) { podDef =>
          assumeValid(podDefValidator().apply(podDef)) {
            normalized(podDef, podNormalizer) { normalizedPodDef =>
              val pod = Raml.fromRaml(normalizedPodDef).copy(version = clock.now())
              assumeValid(PodsValidation.pluginValidators(pluginManager).apply(pod)) {
                authorized(CreateRunSpec, pod).apply {
                  val planCreation: Future[DeploymentPlan] = async {
                    val deployment = await(podManager.create(pod, force))

                    val ip = clientIp.getAddress().toString
                    eventBus.publish(PodEvent(ip, uri.toString(), PodEvent.Created))

                    deployment
                  }
                  onSuccess(planCreation) { plan =>
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

  def update(podId: PathId): Route = ???

  def findAll(): Route =
    authenticated.apply { implicit identity =>

      def isAuthorized(pod: PodDefinition): Boolean = authorizer.isAuthorized(identity, ViewRunSpec, pod)
      val pods = podManager.findAll(isAuthorized)

      val ramlPods: Seq[raml.Pod] = pods.map(PodConversion.podRamlWriter.write)
      complete(ramlPods)
    }

  def find(podId: PathId): Route =
    authenticated.apply { implicit identity =>
      podManager.find(podId) match {
        case None =>
          reject(Rejections.EntityNotFound.noPod(podId))
        case Some(pod) =>
          authorized(ViewRunSpec, pod).apply {
            val ramlPod = PodConversion.podRamlWriter.write(pod)
            complete(ramlPod)
          }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  def remove(podId: PathId): Route =
    authenticated.apply { implicit identity =>
      (extractClientIP & forceParameter & extractUri) { (clientIp, force, uri) =>
        podManager.find(podId) match {
          case None =>
            reject(Rejections.EntityNotFound.noPod(podId))
          case Some(pod) =>
            authorized(DeleteRunSpec, pod).apply {
              val deletion: Future[DeploymentPlan] = async {
                val plan = await(podManager.delete(podId, force))

                val ip = clientIp.getAddress().toString
                eventBus.publish(PodEvent(ip, uri.toString, PodEvent.Deleted))

                plan
              }

              onSuccess(deletion) { plan =>
                val responseHeaders = Seq(
                  Location(Uri(pod.id.toString)),
                  Headers.`Marathon-Deployment-Id`(plan.id)
                )
                complete((StatusCodes.Accepted, responseHeaders))
              }
            }
        }
      }
    }

  @SuppressWarnings(Array("OptionGet"))
  def status(podId: PathId): Route =
    authenticated.apply { implicit identity =>
      podManager.find(podId) match {
        case None =>
          reject(Rejections.EntityNotFound.noPod(podId))
        case Some(pod) =>
          authorized(ViewRunSpec, pod).apply {
            onSuccess(podStatusService.selectPodStatus(podId)) { maybeStatus =>
              // If selectPodStatus returns None this is a bug since find(podId) already verifies that the pod exists.
              // We don't filter the pods with an authorization since we check for authorization before.
              val status: raml.PodStatus = maybeStatus.getOrElse(throw new IllegalStateException(s"Status for pod '$podId' was none even though pod existed at start of request."))
              complete((StatusCodes.OK, status))
            }
          }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  def status(podId: PathId): Route =
    authenticated.apply { implicit identity =>
      podManager.find(podId) match {
        case None =>
          reject(Rejections.EntityNotFound.noPod(podId))
        case Some(pod) =>
          authorized(ViewRunSpec, pod).apply {
            onSuccess(podStatusService.selectPodStatus(podId)) { maybeStatus =>
              val status = maybeStatus.get // If selectPodStatus returns None this is a bug.
              val ramlStatus = PodStatusConversion.podInstanceStatusRamlWriter.write(status)
              complete("")
            }
          }
      }
    }

  def versions(podId: PathId): Route = ???

  def version(podId: PathId, v: String): Route = ???

  def allStatus(): Route =
    authenticated.apply { implicit identity =>
      def isAuthorized(pod: PodDefinition): Boolean = authorizer.isAuthorized(identity, ViewRunSpec, pod)

      val filteredPods = Source(podManager.ids())
        .mapAsync(Int.MaxValue) { id =>
          podStatusService.selectPodStatus(id, isAuthorized)
        }
        .mapConcat((maybeStatus: Option[raml.PodStatus]) => maybeStatus.toList) // flatten
        .runWith(Sink.seq)

      complete(filteredPods)
    }

  def killInstance(instanceId: Instance.Id): Route = ???

  def killInstances(podId: PathId): Route = ???

  // format: OFF
  override val route: Route =
    asLeader(electionService) {
      head {
        capability()
      } ~
      get {
        pathEndOrSingleSlash {
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

}
