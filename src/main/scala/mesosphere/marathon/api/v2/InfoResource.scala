package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ LeaderInfo, MarathonMediaType, RestResource }
import mesosphere.marathon.core.flow.LaunchTokenConfig
import mesosphere.marathon.core.launcher.OfferProcessorConfig
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import mesosphere.marathon.{ BuildInfo, DebugConf, LeaderProxyConf, MarathonConf, MarathonSchedulerService }
import mesosphere.util.ConfigToMap
import mesosphere.util.state.MesosLeaderInfo
import play.api.libs.json.Json.{ toJsFieldJsValueWrapper }
import play.api.libs.json.{ JsValue, Writes, JsObject, Json }

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    schedulerService: MarathonSchedulerService,
    mesosLeaderInfo: MesosLeaderInfo,
    leaderInfo: LeaderInfo,
  // format: OFF
  protected val config: MarathonConf
    with HttpConf
    with EventConfiguration
    with HttpEventConfiguration
    with LeaderProxyConf
    with OfferMatcherManagerConfig
    with OfferProcessorConfig
    with DebugConf
    with LaunchQueueConfig
    with LaunchTokenConfig
) extends RestResource {
  // format: ON

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Json.obj(
    ConfigToMap.convertToMap(config).map {
      { case (x, y) => (x, toJsFieldJsValueWrapper(y)) }
    }.toSeq: _*
  )

  // Zookeeper congiurations
  private[this] lazy val zookeeperConfigValues = Json.obj(
    "zk" -> config.zooKeeperUrl(),
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
      case _                     => Json.obj()
    }

    Json.obj(
      "type" -> config.eventSubscriber.get
    ) ++ eventConfig
  }

  private[this] lazy val httpConfigValues = Json.obj(
    "assets_path" -> config.assetsFileSystemPath.get,
    "http_port" -> config.httpPort.get,
    "https_port" -> config.httpsPort.get
  )

  @GET
  @Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
  def index(): Response = {
    val mesosLeaderUiUrl = Json.obj("mesos_leader_ui_url" -> mesosLeaderInfo.currentLeaderUrl)
    Response.ok(
      jsonObjString(
        "name" -> BuildInfo.name,
        "version" -> BuildInfo.version,
        "elected" -> leaderInfo.elected,
        "leader" -> leaderInfo.currentLeaderHostPort(),
        "frameworkId" -> schedulerService.frameworkId.map(_.getValue),
        "marathon_config" -> (marathonConfigValues ++ mesosLeaderUiUrl),
        "zookeeper_config" -> zookeeperConfigValues,
        "event_subscriber" -> config.eventSubscriber.get.map(_ => eventHandlerConfigValues),
        "http_config" -> httpConfigValues)).build()
  }
}
