package mesosphere.marathon.tasks

import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.mesos.TaskBuilder
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class DefaultTaskFactory @Inject() (
  taskIdUtil: TaskIdUtil,
  config: MarathonConf,
  clock: Clock)
    extends TaskFactory {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def newTask(app: AppDefinition, offer: Offer, runningTasks: Iterable[MarathonTask]): Option[CreatedTask] = {
    log.debug("newTask")

    new TaskBuilder(app, taskIdUtil.newTaskId, config).buildIfMatches(offer, runningTasks).map {
      case (taskInfo, ports) =>
        CreatedTask(
          taskInfo,
          MarathonTasks.makeTask(
            id = taskInfo.getTaskId.getValue,
            host = offer.getHostname,
            ports = ports,
            attributes = offer.getAttributesList.asScala,
            version = app.version,
            now = clock.now(),
            slaveId = offer.getSlaveId
          )
        )
    }
  }
}
