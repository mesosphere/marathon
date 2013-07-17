package mesosphere.marathon

import org.apache.mesos.Protos.{TaskID, TaskState, TaskStatus, TaskInfo}
import java.net.InetSocketAddress
import scala.collection.JavaConverters._
import scala.collection._
import mesosphere.mesos.TaskBuilder
import javax.inject.{Named, Inject}
import com.twitter.common.zookeeper.{ServerSetImpl, ServerSet, ZooKeeperClient}
import com.google.common.collect.{ImmutableSet, Maps}
import java.util.logging.Logger
import com.twitter.common.zookeeper.ServerSet.EndpointStatus
import com.twitter.common.net.pool.DynamicHostSet
import com.twitter.thrift.ServiceInstance

/**
 * Registers apps in Zookeeper via ServerSet
 * TODO(TK): implement in the executor, so we don't lose track of all endpoints when Marathon goes down.
 *
 * @author Tobi Knaup
 */

class AppRegistry @Inject()(
    zkClient: ZooKeeperClient,
    @Named(ModuleNames.NAMED_SERVER_SET_PATH) zkPath: String) {

  val defaultShardId = 0
  val log = Logger.getLogger(getClass.getName)
  val serverSets = mutable.Map.empty[String, ServerSet]
  val addresses = mutable.Map.empty[TaskID, InetSocketAddress]
  val endpointStatuses = mutable.Map.empty[TaskID, EndpointStatus]
  val appInstanceMap = new mutable.HashMap[String, Set[ServiceInstance]] with
    mutable.SynchronizedMap[String, Set[ServiceInstance]]

  // Get server sets on startup
  zkClient.get().getChildren(zkPath, false).asScala.foreach(startUp)


  def starting(appName: String, hostname: String, task: TaskInfo) {
    // TODO wrap TaskInfo into something that gives us access to the port
    val port = task.getResourcesList.asScala
      .find(_.getName == TaskBuilder.portsResourceName)
      .map(_.getRanges.getRange(0).getBegin)

    assert(port.isDefined, "Task doesn't have a port.")

    val address = new InetSocketAddress(hostname, port.get.toInt)
    addresses(task.getTaskId) = address
  }

  def statusUpdate(appName: String, status: TaskStatus) {
    if (!serverSets.contains(appName)) {
      log.warning(s"No server set for $appName")
      return
    }

    if (status.getState == TaskState.TASK_RUNNING) {
      addresses.remove(status.getTaskId) match {
        case Some(address) => {
          val endpointStatus = serverSets(appName)
            .join(address, Maps.newHashMap[String, InetSocketAddress](), defaultShardId)
          endpointStatuses(status.getTaskId) = endpointStatus
        }
        case None => log.warning("No address for task %s".format(status.getTaskId))
      }
    }
    else if (status.getState == TaskState.TASK_FAILED ||
        status.getState == TaskState.TASK_FINISHED ||
        status.getState == TaskState.TASK_KILLED ||
        status.getState == TaskState.TASK_LOST) {
      endpointStatuses.remove(status.getTaskId) match {
        case Some(endpointStatus) => endpointStatus.leave()
        case None => log.warning("No endpoint status for task %s".format(status.getTaskId))
      }
    }
  }

  def startUp(appName: String) {
    val serverSet = new ServerSetImpl(zkClient, s"$zkPath/$appName")
    serverSets(appName) = serverSet
    watchServerSet(appName, serverSet)
  }

  def shutDown(appName: String) {
    serverSets.remove(appName)
  }

  private def watchServerSet(appName: String, serverSet: ServerSet) {
    serverSet.watch(new DynamicHostSet.HostChangeMonitor[ServiceInstance] {
      def onChange(hostSet: ImmutableSet[ServiceInstance]) {
        appInstanceMap(appName) = hostSet.asScala
        log.fine(s"Host set for app $appName changed")
      }
    })
  }
}