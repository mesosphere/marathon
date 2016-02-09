package mesosphere.marathon.tasks

import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
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

  override def newTask(app: AppDefinition, offer: Offer, runningTasks: Iterable[Task]): Option[CreatedTask] = {
    log.debug("newTask")

    new TaskBuilder(app, taskIdUtil.newTaskId, config).buildIfMatches(offer, runningTasks).map {
      case (taskInfo, ports) =>
        val task = Task(
          taskId = Task.Id(taskInfo.getTaskId),
          agentInfo = Task.AgentInfo(
            host = offer.getHostname,
            agentId = Some(offer.getSlaveId.getValue),
            attributes = offer.getAttributesList.asScala
          ),
          launched = Some(
            Task.Launched(
              appVersion = app.version,
              status = Task.Status(
                stagedAt = clock.now()
              ),
              networking = Task.HostPorts(ports)
            )
          )
        )
        CreatedTask(taskInfo, task)
    }
  }
}
