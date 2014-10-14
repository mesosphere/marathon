package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import mesosphere.marathon.{ MarathonSchedulerService, BuildInfo, MarathonConf }

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (
    schedulerService: MarathonSchedulerService,
    conf: MarathonConf with HttpConf with EventConfiguration with HttpEventConfiguration) {

  // Marathon configurations
  private[this] lazy val marathonConfigValues = Map(
    "master" -> conf.mesosMaster.get,
    "failover_timeout" -> conf.mesosFailoverTimeout.get,
    "ha" -> conf.highlyAvailable.get,
    "checkpoint" -> conf.checkpoint.get,
    "local_port_min" -> conf.localPortMin.get,
    "local_port_max" -> conf.localPortMax.get,
    "executor" -> conf.defaultExecutor.get,
    "hostname" -> conf.hostname.get,
    "mesos_role" -> conf.mesosRole.get,
    "task_launch_timeout" -> conf.taskLaunchTimeout.get,
    "reconciliation_initial_delay" -> conf.reconciliationInitialDelay.get,
    "reconciliation_interval" -> conf.reconciliationInterval.get,
    "mesos_user" -> conf.mesosUser.get)

  // Zookeeper congiurations
  private[this] lazy val zookeeperConfigValues = Map(
    "zk_timeout" -> conf.zooKeeperTimeout.get,
    "zk" -> conf.zooKeeperUrl.get,
    "zk_hosts" -> conf.zkHosts,
    "zk_path" -> conf.zkPath,
    "zk_timeout" -> conf.zkTimeoutDuration,
    "zk_future_timeout" -> conf.zkFutureTimeout)

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
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index(): Response = {
    Response.ok(
      Map(
        "name" -> BuildInfo.name,
        "version" -> BuildInfo.version,
        "leader" -> schedulerService.getLeader,
        "frameworkId" -> schedulerService.frameworkId.map(_.getValue),
        "marathon_config" -> marathonConfigValues,
        "zookeeper_config" -> zookeeperConfigValues,
        "event_subscriber" -> conf.eventSubscriber.get.map(_ => eventHandlerConfigValues),
        "http_config" -> httpConfigValues)).build()
  }
}
