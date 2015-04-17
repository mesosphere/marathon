package mesosphere.marathon.tasks

import javax.inject.Named

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.TaskBuilder
import org.apache.mesos.Protos.{ TaskInfo, Offer }
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._

class DefaultTaskFactory @Inject() (
  taskIdUtil: TaskIdUtil,
  taskTracker: TaskTracker,
  config: MarathonConf,
  @Named("restMapper") mapper: ObjectMapper)
    extends TaskFactory {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def newTask(app: AppDefinition, offer: Offer): Option[(TaskInfo, MarathonTask)] = {
    log.debug("newTask")

    new TaskBuilder(app, taskIdUtil.newTaskId, taskTracker, config, mapper).buildIfMatches(offer).map {
      case (taskInfo, ports) =>
        taskInfo -> MarathonTasks.makeTask(
          taskInfo.getTaskId.getValue, offer.getHostname, ports,
          offer.getAttributesList.asScala, app.version)
    }
  }
}
