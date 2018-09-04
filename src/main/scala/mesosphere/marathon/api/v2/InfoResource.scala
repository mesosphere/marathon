package mesosphere.marathon
package api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{Context, MediaType, Response}
import javax.ws.rs.{Consumes, GET, Path, Produces}

import com.google.inject.Inject
import mesosphere.marathon.HttpConf
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.MesosLeaderInfo
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    mesosLeaderInfo: MesosLeaderInfo,
    frameworkIdRepository: FrameworkIdRepository,
    electionService: ElectionService,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    protected val config: MarathonConf with HttpConf
)(implicit val executionContext: ExecutionContext) extends AuthResource {

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Json.obj(
    "access_control_allow_origin" -> config.accessControlAllowOrigin.toOption,
    "checkpoint" -> config.checkpoint.toOption,
    "decline_offer_duration" -> config.declineOfferDuration.toOption,
    "default_network_name" -> config.defaultNetworkName.toOption,
    "env_vars_prefix" -> config.envVarsPrefix.toOption,
    "executor" -> config.defaultExecutor.toOption,
    "failover_timeout" -> config.mesosFailoverTimeout.toOption,
    "features" -> config.availableFeatures,
    "framework_name" -> config.frameworkName.toOption,
    "ha" -> config.highlyAvailable.toOption,
    "hostname" -> config.hostname.toOption,
    "launch_token" -> config.launchTokens.toOption,
    "launch_token_refresh_interval" -> config.launchTokenRefreshInterval.toOption,
    "leader_proxy_connection_timeout_ms" -> config.leaderProxyConnectionTimeout.toOption,
    "leader_proxy_read_timeout_ms" -> config.leaderProxyReadTimeout.toOption,
    "local_port_max" -> config.localPortMax.toOption,
    "local_port_min" -> config.localPortMin.toOption,
    "master" -> config.mesosMaster().redactedConnectionString,
    "max_instances_per_offer" -> config.maxInstancesPerOffer.toOption,
    "mesos_bridge_name" -> config.mesosBridgeName.toOption,
    "mesos_heartbeat_failure_threshold" -> config.mesosHeartbeatFailureThreshold.toOption,
    "mesos_heartbeat_interval" -> config.mesosHeartbeatInterval.toOption,
    "mesos_leader_ui_url" -> config.mesosLeaderUiUrl.toOption,
    "mesos_role" -> config.mesosRole.toOption,
    "mesos_user" -> config.mesosUser.toOption,
    "min_revive_offers_interval" -> config.minReviveOffersInterval.toOption,
    "offer_matching_timeout" -> config.offerMatchingTimeout.toOption.map(_.toMillis),
    "on_elected_prepare_timeout" -> config.onElectedPrepareTimeout.toOption,
    "reconciliation_initial_delay" -> config.reconciliationInitialDelay.toOption,
    "reconciliation_interval" -> config.reconciliationInterval.toOption,
    "revive_offers_for_new_apps" -> config.reviveOffersForNewApps.toOption,
    "revive_offers_repetitions" -> config.reviveOffersRepetitions.toOption,
    "scale_apps_initial_delay" -> config.scaleAppsInitialDelay.toOption,
    "scale_apps_interval" -> config.scaleAppsInterval.toOption,
    "store_cache" -> config.storeCache.toOption,
    "task_launch_confirm_timeout" -> config.taskLaunchConfirmTimeout.toOption,
    "task_launch_timeout" -> config.taskLaunchTimeout.toOption,
    "task_lost_expunge_initial_delay" -> config.taskLostExpungeInitialDelay.toMillis,
    "task_lost_expunge_interval" -> config.taskLostExpungeInterval.toMillis,
    "task_reservation_timeout" -> config.taskReservationTimeout.toOption,
    "webui_url" -> config.webuiUrl.toOption
  )

  // ZooKeeper congiurations
  private[this] lazy val zookeeperConfigValues = Json.obj(
    "zk" -> config.zooKeeperUrl().redactedConnectionString,
    "zk_compression" -> config.zooKeeperCompressionEnabled.toOption,
    "zk_compression_threshold" -> config.zooKeeperCompressionThreshold.toOption,
    "zk_connection_timeout" -> config.zooKeeperConnectionTimeout(),
    "zk_max_node_size" -> config.zooKeeperMaxNodeSize.toOption,
    "zk_max_versions" -> config.maxVersions(),
    "zk_session_timeout" -> config.zooKeeperSessionTimeout(),
    "zk_timeout" -> config.zooKeeperTimeout()
  )

  private[this] lazy val httpConfigValues = Json.obj(
    "http_port" -> config.httpPort.toOption,
    "https_port" -> config.httpsPort.toOption
  )

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, AuthorizedResource.SystemConfig) {
      val mesosLeaderUiUrl = Json.obj("mesos_leader_ui_url" -> mesosLeaderInfo.currentLeaderUrl)
      Response.ok(
        jsonObjString(
          "name" -> BuildInfo.name,
          "version" -> BuildInfo.version.toString(),
          "buildref" -> BuildInfo.buildref,
          "elected" -> electionService.isLeader,
          "leader" -> electionService.leaderHostPort,
          "frameworkId" -> result(frameworkIdRepository.get()).map(_.id),
          "marathon_config" -> (marathonConfigValues ++ mesosLeaderUiUrl),
          "zookeeper_config" -> zookeeperConfigValues,
          "http_config" -> httpConfigValues)).build()
    }
  }
}
