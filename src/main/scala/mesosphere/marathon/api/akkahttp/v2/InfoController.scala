package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedResource, Authorizer, ViewResource }
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.MesosLeaderInfo
import play.api.libs.json._

import scala.async.Async._
import scala.concurrent.ExecutionContext

case class InfoController(
    val mesosLeaderInfo: MesosLeaderInfo,
    val frameworkIdRepository: FrameworkIdRepository,
    val config: MarathonConf)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService,
    val executionContext: ExecutionContext) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  // Marathon configurations
  val marathonConfigValues = Json.obj(
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

  // ZooKeeper configurations
  val zookeeperConfigValues = Json.obj(
    "zk" -> s"zk://${config.zkHosts}${config.zkPath}",
    "zk_timeout" -> config.zooKeeperTimeout(),
    "zk_connection_timeout" -> config.zooKeeperConnectionTimeout(),
    "zk_session_timeout" -> config.zooKeeperSessionTimeout(),
    "zk_max_versions" -> config.maxVersions()
  )

  //  lazy val httpConfigValues = Json.obj(
  //    "http_port" -> config.httpPort.get,
  //    "https_port" -> config.httpsPort.get
  //  )

  def info(): Route =
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        complete {
          async {
            val frameworkId = await(frameworkIdRepository.get()).map(_.id)

            // Current lead might change so we have to fetch it each time.
            val mesosLeaderUiUrl = Json.obj("mesos_leader_ui_url" -> mesosLeaderInfo.currentLeaderUrl)

            Json.obj(
              "name" -> BuildInfo.name,
              "name" -> BuildInfo.name,
              "version" -> BuildInfo.version,
              "buildref" -> BuildInfo.buildref,
              "elected" -> electionService.isLeader,
              "leader" -> electionService.leaderHostPort,
              "frameworkId" -> frameworkId,
              "marathon_config" -> (marathonConfigValues ++ mesosLeaderUiUrl),
              "zookeeper_config" -> zookeeperConfigValues
            //          "http_config" -> httpConfigValues)).build()
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
