package mesosphere.marathon
package api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{ Context, MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.MesosLeaderInfo
import play.api.libs.json.Json

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    mesosLeaderInfo: MesosLeaderInfo,
    frameworkIdRepository: FrameworkIdRepository,
    electionService: ElectionService,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    protected val config: MarathonConf with HttpConf
) extends AuthResource {

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Json.obj(
    "access_control_allow_origin" -> config.accessControlAllowOrigin.get,
    "checkpoint" -> config.checkpoint.get,
    "decline_offer_duration" -> config.declineOfferDuration.get,
    "default_network_name" -> config.defaultNetworkName.get,
    "env_vars_prefix" -> config.envVarsPrefix.get,
    "executor" -> config.defaultExecutor.get,
    "failover_timeout" -> config.mesosFailoverTimeout.get,
    "features" -> config.availableFeatures,
    "framework_name" -> config.frameworkName.get,
    "ha" -> config.highlyAvailable.get,
    "hostname" -> config.hostname.get,
    "launch_token" -> config.launchTokens.get,
    "launch_token_refresh_interval" -> config.launchTokenRefreshInterval.get,
    "leader_proxy_connection_timeout_ms" -> config.leaderProxyConnectionTimeout.get,
    "leader_proxy_read_timeout_ms" -> config.leaderProxyReadTimeout.get,
    "local_port_max" -> config.localPortMax.get,
    "local_port_min" -> config.localPortMin.get,
    "master" -> config.mesosMaster.get,
    "max_instances_per_offer" -> config.maxInstancesPerOffer.get,
    "mesos_bridge_name" -> config.mesosBridgeName.get,
    "mesos_heartbeat_failure_threshold" -> config.mesosHeartbeatFailureThreshold.get,
    "mesos_heartbeat_interval" -> config.mesosHeartbeatInterval.get,
    "mesos_leader_ui_url" -> config.mesosLeaderUiUrl.get,
    "mesos_role" -> config.mesosRole.get,
    "mesos_user" -> config.mesosUser.get,
    "min_revive_offers_interval" -> config.minReviveOffersInterval.get,
    "offer_matching_timeout" -> config.offerMatchingTimeout.get.map(_.toMillis),
    "on_elected_prepare_timeout" -> config.onElectedPrepareTimeout.get,
    "reconciliation_initial_delay" -> config.reconciliationInitialDelay.get,
    "reconciliation_interval" -> config.reconciliationInterval.get,
    "revive_offers_for_new_apps" -> config.reviveOffersForNewApps.get,
    "revive_offers_repetitions" -> config.reviveOffersRepetitions.get,
    "scale_apps_initial_delay" -> config.scaleAppsInitialDelay.get,
    "scale_apps_interval" -> config.scaleAppsInterval.get,
    "store_cache" -> config.storeCache.get,
    "task_launch_confirm_timeout" -> config.taskLaunchConfirmTimeout.get,
    "task_launch_timeout" -> config.taskLaunchTimeout.get,
    "task_lost_expunge_initial_delay" -> config.taskLostExpungeInitialDelay.toMillis,
    "task_lost_expunge_interval" -> config.taskLostExpungeInterval.toMillis,
    "task_reservation_timeout" -> config.taskReservationTimeout.get,
    "webui_url" -> config.webuiUrl.get
  )

  // ZooKeeper congiurations
  private[this] lazy val zookeeperConfigValues = Json.obj(
    "zk" -> s"zk://${config.zkHosts}${config.zkPath}",
    "zk_compression" -> config.zooKeeperCompressionEnabled.get,
    "zk_compression_threshold" -> config.zooKeeperCompressionThreshold.get,
    "zk_connection_timeout" -> config.zooKeeperConnectionTimeout(),
    "zk_max_node_size" -> config.zooKeeperMaxNodeSize.get,
    "zk_max_versions" -> config.maxVersions(),
    "zk_session_timeout" -> config.zooKeeperSessionTimeout(),
    "zk_timeout" -> config.zooKeeperTimeout()
  )

  private[this] lazy val httpConfigValues = Json.obj(
    "http_port" -> config.httpPort.get,
    "https_port" -> config.httpsPort.get
  )

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, AuthorizedResource.SystemConfig) {
      val mesosLeaderUiUrl = Json.obj("mesos_leader_ui_url" -> mesosLeaderInfo.currentLeaderUrl)
      Response.ok(
        jsonObjString(
          "name" -> BuildInfo.name,
          "version" -> BuildInfo.version,
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
