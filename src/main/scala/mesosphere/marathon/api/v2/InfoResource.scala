package mesosphere.marathon.api.v2

import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.{ Consumes, GET, Path, Produces }

import com.google.inject.Inject
import mesosphere.marathon.{ MarathonSchedulerService, BuildInfo, MarathonConf }

@Path("v2/info")
@Consumes(Array(MediaType.APPLICATION_JSON))
class InfoResource @Inject() (schedulerService: MarathonSchedulerService, marathonConf: MarathonConf) {

  // Marathon configurations
  private[this] val marathonConfigValues = Map(
    "master" -> marathonConf.mesosMaster.get,
    "failover_timeout" -> marathonConf.mesosFailoverTimeout.get,
    "ha" -> marathonConf.highlyAvailable.get,
    "checkpoint" -> marathonConf.checkpoint.get,
    "local_port_min" -> marathonConf.localPortMin.get,
    "local_port_max" -> marathonConf.localPortMax.get,
    "executor" -> marathonConf.defaultExecutor.get,
    "hostname" -> marathonConf.hostname.get,
    "mesos_role" -> marathonConf.mesosRole.get,
    "task_launch_timeout" -> marathonConf.taskLaunchTimeout.get,
    "reconciliation_initial_delay" -> marathonConf.reconciliationInitialDelay.get,
    "reconciliation_interval" -> marathonConf.reconciliationInterval.get,
    "mesos_user" -> marathonConf.mesosUser.get)

  // Zookeeper congiurations
  private[this] val zookeeperConfigValues = Map(
    "zk_hosts" -> marathonConf.zooKeeperHostString.get,
    "zk_state" -> marathonConf.zooKeeperPath.get,
    "zk_timeout" -> marathonConf.zooKeeperTimeout.get,
    "zk" -> marathonConf.zooKeeperUrl.get,
    "zk_hosts" -> marathonConf.zkHosts,
    "zk_path" -> marathonConf.zkPath,
    "zk_timeout" -> marathonConf.zkTimeoutDuration,
    "zk_future_timeout" -> marathonConf.zkFutureTimeout)

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
        "zookeeper_config" -> zookeeperConfigValues)).build()
  }
}
