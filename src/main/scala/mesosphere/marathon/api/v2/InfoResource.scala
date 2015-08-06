package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ MarathonMediaType, LeaderInfo }
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import mesosphere.marathon.{ BuildInfo, LeaderProxyConf, MarathonConf, MarathonSchedulerService }
import mesosphere.util.state.MesosLeaderInfo

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    schedulerService: MarathonSchedulerService,
    mesosLeaderInfo: MesosLeaderInfo,
    leaderInfo: LeaderInfo,
    conf: MarathonConf with HttpConf with EventConfiguration with HttpEventConfiguration with LeaderProxyConf) {

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Map(
    "master" -> conf.mesosMaster.get,
    "failover_timeout" -> conf.mesosFailoverTimeout.get,
    "framework_name" -> conf.frameworkName.get,
    "ha" -> conf.highlyAvailable.get,
    "checkpoint" -> conf.checkpoint.get,
    "local_port_min" -> conf.localPortMin.get,
    "local_port_max" -> conf.localPortMax.get,
    "executor" -> conf.defaultExecutor.get,
    "hostname" -> conf.hostname.get,
    "webui_url" -> conf.webuiUrl.get,
    "mesos_role" -> conf.mesosRole.get,
    "task_launch_timeout" -> conf.taskLaunchTimeout.get,
    "reconciliation_initial_delay" -> conf.reconciliationInitialDelay.get,
    "reconciliation_interval" -> conf.reconciliationInterval.get,
    "marathon_store_timeout" -> conf.marathonStoreTimeout.get,
    "mesos_user" -> conf.mesosUser.get,
    "leader_proxy_connection_timeout_ms" -> conf.leaderProxyConnectionTimeout.get,
    "leader_proxy_read_timeout_ms" -> conf.leaderProxyReadTimeout.get)

  // Zookeeper congiurations
  private[this] lazy val zookeeperConfigValues = Map[String, Any] (
    "zk" -> conf.zooKeeperUrl(),
    "zk_timeout" -> conf.zooKeeperTimeout(),
    "zk_session_timeout" -> conf.zooKeeperSessionTimeout(),
    "zk_max_versions" -> conf.zooKeeperMaxVersions()
  )

  private[this] lazy val eventHandlerConfigValues = {
    def httpEventConfig: Map[String, Option[Seq[String]]] = Map(
      "http_endpoints" -> conf.httpEventEndpoints.get
    )

    def eventConfig(): Map[String, Option[Seq[String]]] = conf.eventSubscriber.get match {
      case Some("http_callback") => httpEventConfig
      case _                     => Map()
    }

    Map(
      "type" -> conf.eventSubscriber.get
    ) ++ eventConfig
  }

  private[this] lazy val httpConfigValues = Map(
    "assets_path" -> conf.assetsFileSystemPath.get,
    "http_port" -> conf.httpPort.get,
    "https_port" -> conf.httpsPort.get
  )

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    val mesosLeaderUiUrl = "mesos_leader_ui_url" -> mesosLeaderInfo.currentLeaderUrl
    Response.ok(
      Map(
        "name" -> BuildInfo.name,
        "version" -> BuildInfo.version,
        "elected" -> leaderInfo.elected,
        "leader" -> leaderInfo.currentLeaderHostPort(),
        "frameworkId" -> schedulerService.frameworkId.map(_.getValue),
        "marathon_config" -> (marathonConfigValues + mesosLeaderUiUrl),
        "zookeeper_config" -> zookeeperConfigValues,
        "event_subscriber" -> conf.eventSubscriber.get.map(_ => eventHandlerConfigValues),
        "http_config" -> httpConfigValues)).build()
  }
}
