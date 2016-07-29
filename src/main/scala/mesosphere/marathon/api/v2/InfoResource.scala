package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{ Context, MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.BuildInfo
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.util.state.MesosLeaderInfo
import play.api.libs.json.{ JsObject, Json }

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    schedulerService: MarathonSchedulerService,
    mesosLeaderInfo: MesosLeaderInfo,
    electionService: ElectionService,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    protected val config: MarathonConf with HttpConf
) extends AuthResource {

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Json.obj(
    "master" -> config.mesosMaster.get,
    "failover_timeout" -> config.mesosFailoverTimeout.get,
    "framework_name" -> config.frameworkName.get,
    "ha" -> config.highlyAvailable.get,
    "checkpoint" -> config.checkpoint.get,
    "local_port_min" -> config.localPortMin.get,
    "local_port_max" -> config.localPortMax.get,
    "executor" -> config.defaultExecutor.get,
    "hostname" -> config.hostname.get,
    "webui_url" -> config.webuiUrl.get,
    "mesos_role" -> config.mesosRole.get,
    "task_launch_timeout" -> config.taskLaunchTimeout.get,
    "task_reservation_timeout" -> config.taskReservationTimeout.get,
    "reconciliation_initial_delay" -> config.reconciliationInitialDelay.get,
    "reconciliation_interval" -> config.reconciliationInterval.get,
    "mesos_user" -> config.mesosUser.get,
    "leader_proxy_connection_timeout_ms" -> config.leaderProxyConnectionTimeout.get,
    "leader_proxy_read_timeout_ms" -> config.leaderProxyReadTimeout.get,
    "features" -> config.availableFeatures
  )

  // ZooKeeper congiurations
  private[this] lazy val zookeeperConfigValues = Json.obj(
    "zk" -> s"zk://${config.zkHosts}${config.zkPath}",
    "zk_timeout" -> config.zooKeeperTimeout(),
    "zk_session_timeout" -> config.zooKeeperSessionTimeout(),
    "zk_max_versions" -> config.zooKeeperMaxVersions()
  )

  private[this] lazy val eventHandlerConfigValues = {
    def httpEventConfig: JsObject = Json.obj(
      "http_endpoints" -> config.httpEventEndpoints.get
    )

    def eventConfig(): JsObject = config.eventSubscriber.get match {
      case Some("http_callback") => httpEventConfig
      case _ => Json.obj()
    }

    Json.obj(
      "type" -> config.eventSubscriber.get
    ) ++ eventConfig
  }

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
          "frameworkId" -> schedulerService.frameworkId.map(_.getValue),
          "marathon_config" -> (marathonConfigValues ++ mesosLeaderUiUrl),
          "zookeeper_config" -> zookeeperConfigValues,
          "event_subscriber" -> config.eventSubscriber.get.map(_ => eventHandlerConfigValues),
          "http_config" -> httpConfigValues)).build()
    }
  }
}
