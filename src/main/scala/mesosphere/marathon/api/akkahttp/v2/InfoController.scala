package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedResource, Authorizer, ViewResource }
import mesosphere.marathon.raml.{ HttpConfig, MarathonConfig, MarathonInfo, ZooKeeperConfig }
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.MesosLeaderInfo

import scala.async.Async._
import scala.concurrent.ExecutionContext

case class InfoController(
    val mesosLeaderInfo: MesosLeaderInfo,
    val frameworkIdRepository: FrameworkIdRepository,
    val config: MarathonConf with HttpConf)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService,
    val executionContext: ExecutionContext) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  val marathonConfig = MarathonConfig(
    access_control_allow_origin = config.accessControlAllowOrigin.get.foldLeft(Seq.empty[String]) { (_, origin) =>
      origin
    },
    checkpoint = config.checkpoint.get,
    decline_offer_duration = config.declineOfferDuration.get,
    default_network_name = config.defaultNetworkName.get,
    env_vars_prefix = config.envVarsPrefix.get,
    executor = config.defaultExecutor.get,
    failover_timeout = config.mesosFailoverTimeout.get,
    features = config.availableFeatures.toList,
    framework_name = config.frameworkName.get,
    ha = config.highlyAvailable.get,
    hostname = config.hostname.get,
    launch_token = config.launchTokens.get,
    launch_token_refresh_interval = config.launchTokenRefreshInterval.get,
    leader_proxy_connection_timeout_ms = config.leaderProxyConnectionTimeout.get,
    leader_proxy_read_timeout_ms = config.leaderProxyReadTimeout.get,
    local_port_max = config.localPortMax.get,
    local_port_min = config.localPortMin.get,
    master = config.mesosMaster.get,
    max_instances_per_offer = config.maxInstancesPerOffer.get,
    mesos_bridge_name = config.mesosBridgeName.get,
    mesos_heartbeat_failure_threshold = config.mesosHeartbeatFailureThreshold.get,
    mesos_heartbeat_interval = config.mesosHeartbeatInterval.get,
    mesos_leader_ui_url = config.mesosLeaderUiUrl.get,
    mesos_role = config.mesosRole.get,
    mesos_user = config.mesosUser.get,
    min_revive_offers_interval = config.minReviveOffersInterval.get,
    offer_matching_timeout = config.offerMatchingTimeout.get.map(_.toMillis),
    on_elected_prepare_timeout = config.onElectedPrepareTimeout.get,
    reconciliation_initial_delay = config.reconciliationInitialDelay.get,
    reconciliation_interval = config.reconciliationInterval.get,
    revive_offers_for_new_apps = config.reviveOffersForNewApps.get,
    revive_offers_repetitions = config.reviveOffersRepetitions.get,
    scale_apps_initial_delay = config.scaleAppsInitialDelay.get,
    scale_apps_interval = config.scaleAppsInterval.get,
    store_cache = config.storeCache.get,
    task_launch_confirm_timeout = config.taskLaunchConfirmTimeout.get,
    task_launch_timeout = config.taskLaunchTimeout.get,
    task_lost_expunge_initial_delay = config.taskLostExpungeInitialDelay.toMillis,
    task_lost_expunge_interval = config.taskLostExpungeInterval.toMillis,
    task_reservation_timeout = config.taskReservationTimeout.get,
    webui_url = config.webuiUrl.get
  )

  val zookeeperConfig = ZooKeeperConfig(
    zk = s"zk://${config.zkHosts}${config.zkPath}",
    zk_compression = config.zooKeeperCompressionEnabled.get,
    zk_compression_threshold = config.zooKeeperCompressionThreshold.get,
    zk_connection_timeout = config.zooKeeperConnectionTimeout(),
    zk_max_node_size = config.zooKeeperMaxNodeSize.get,
    zk_max_versions = config.maxVersions(),
    zk_session_timeout = config.zooKeeperSessionTimeout(),
    zk_timeout = config.zooKeeperTimeout()
  )

  val httpConfig = HttpConfig(config.httpPort(), config.httpsPort())

  @SuppressWarnings(Array("all")) // async/await
  def info(): Route =
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        complete {
          async {
            val frameworkId = await(frameworkIdRepository.get()).map(_.id)

            MarathonInfo(
              name = BuildInfo.name,
              version = BuildInfo.version,
              buildref = BuildInfo.buildref,
              elected = electionService.isLeader,
              leader = electionService.leaderHostPort,
              frameworkId = frameworkId,
              // Current lead might change so we have to fetch it each time.
              marathon_config = marathonConfig.copy(mesos_leader_ui_url = mesosLeaderInfo.currentLeaderUrl),
              zookeeper_config = zookeeperConfig,
              http_config = httpConfig
            )
          }
        }
      }
    }

  override val route: Route = {
    get {
      pathEndOrSingleSlash {
        info()
      }
    }
  }
}
