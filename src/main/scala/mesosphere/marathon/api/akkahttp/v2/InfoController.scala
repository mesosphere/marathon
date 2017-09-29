package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
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
    val config: MarathonConf)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService,
    val executionContext: ExecutionContext) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._

  val marathonConfig = MarathonConfig(
    config.mesosMaster.get,
    config.mesosFailoverTimeout.get,
    config.frameworkName.get,
    config.highlyAvailable.get,
    config.checkpoint.get,
    config.localPortMin.get,
    config.localPortMax.get,
    config.defaultExecutor.get,
    config.hostname.get,
    config.webuiUrl.get,
    config.mesosRole.get,
    config.taskLaunchTimeout.get,
    config.taskReservationTimeout.get,
    config.reconciliationInitialDelay.get,
    config.reconciliationInterval.get,
    config.mesosUser.get,
    config.leaderProxyConnectionTimeout.get,
    config.leaderProxyReadTimeout.get,
    mesosLeaderInfo.currentLeaderUrl,
    config.availableFeatures.toIndexedSeq
  )

  val zookeeperConfig = ZooKeeperConfig(
    s"zk://${config.zkHosts}${config.zkPath}",
    config.zooKeeperTimeout(),
    config.zooKeeperConnectionTimeout(),
    config.zooKeeperSessionTimeout(),
    config.maxVersions()
  )

  val httpConfig = HttpConfig(8080, 8081)

  def info(): Route =
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        complete {
          async {
            val frameworkId = await(frameworkIdRepository.get()).map(_.id)

            MarathonInfo(
              BuildInfo.name,
              BuildInfo.version,
              BuildInfo.buildref,
              electionService.isLeader,
              electionService.leaderHostPort,
              frameworkId,
              // Current lead might change so we have to fetch it each time.
              marathonConfig.copy(mesos_leader_ui_url = mesosLeaderInfo.currentLeaderUrl),
              zookeeperConfig,
              httpConfig
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
