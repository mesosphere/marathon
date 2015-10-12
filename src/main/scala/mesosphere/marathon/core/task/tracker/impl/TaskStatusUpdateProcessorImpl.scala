package mesosphere.marathon.core.task.tracker.impl

import javax.inject.Named

import akka.actor.ActorRef
import akka.event.EventStream
import com.google.inject.Inject
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.MarathonTaskStatus.WithMesosStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusObservables }
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateProcessor
import mesosphere.marathon.event.{ EventModule, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ PathId, AppRepository, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Processes task status update events, mostly to update the task tracker.
  */
class TaskStatusUpdateProcessorImpl @Inject() (
    taskStatusEmitter: TaskStatusEmitter,
    appRepository: AppRepository,
    launchQueue: LaunchQueue,
    @Named(EventModule.busName) eventBus: EventStream,
    @Named("schedulerActor") schedulerActor: ActorRef,
    taskIdUtil: TaskIdUtil,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder) extends TaskStatusUpdateProcessor {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def publish(incoming: TaskStatusObservables.TaskStatusUpdate): Future[Unit] = incoming match {
    case TaskStatusUpdate(timestamp, taskId, WithMesosStatus(status)) =>
      val appId = taskIdUtil.appId(taskId)

      // forward health changes to the health check manager
      val maybeTask = taskTracker.fetchTask(appId, taskId.getValue)
      for (marathonTask <- maybeTask)
        healthCheckManager.update(status, Timestamp(marathonTask.getVersion))

      import org.apache.mesos.Protos.TaskState._

      import scala.concurrent.ExecutionContext.Implicits.global
      val eventuallyTracked = status.getState match {
        case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
          // Remove from our internal list
          taskTracker.terminated(appId, taskId.getValue).flatMap { taskOption =>
            rateLimit(appId, status, taskOption).map { _ =>
              schedulerActor ! ScaleApp(appId)

              taskOption match {
                case Some(task) => postEvent(status, task)
                case None       => log.warn(s"Task not found. Do not post event for '{}'", taskId.getValue)
              }
            }
          }

        case TASK_RUNNING if !maybeTask.exists(_.hasStartedAt) => // staged, not running
          taskTracker.running(appId, status)
            .map { task => postEvent(status, task) }
            .recover {
              case NonFatal(t) =>
                log.warn(s"Task could not be saved. Do not post event for '${taskId.getValue}'", t)
                driverOpt.foreach(_.killTask(status.getTaskId))
            }

        case TASK_STAGING if !taskTracker.contains(appId) =>
          log.warn(s"Received status update for unknown app $appId, killing task ${status.getTaskId}")
          driverOpt.foreach(_.killTask(status.getTaskId))
          Future.successful(())

        case _ =>
          taskTracker.statusUpdate(appId, status).map {
            case None =>
              log.warn(s"Killing task ${status.getTaskId}")
              driverOpt.foreach(_.killTask(status.getTaskId))
            case _ =>
          }
      }
      eventuallyTracked.map { _ =>
        taskStatusEmitter.publish(incoming)
        driverOpt.foreach(_.acknowledgeStatusUpdate(status))
      }
  }

  private[this] def rateLimit(appId: PathId, status: TaskStatus, taskOption: Option[MarathonTask]): Future[Unit] = {
    taskOption match {
      case Some(task) if status.getState != TaskState.TASK_KILLED =>
        import scala.concurrent.ExecutionContext.Implicits.global
        appRepository.app(appId, Timestamp(task.getVersion)).map { appOption =>
          // It would be nice if we could make sure that the delay gets send
          // to the AppTaskLauncherActor before we continue but that would require quite some work.
          //
          // In production, it should not matter if we do not delay some task launches so much.
          appOption.foreach(launchQueue.addDelay)
        }
      case _ =>
        Future.successful(())
    }
  }

  private[this] def driverOpt = marathonSchedulerDriverHolder.driver

  private[this] def postEvent(status: TaskStatus, task: MarathonTask): Unit = {
    log.info("Sending event notification.")
    import scala.collection.JavaConverters._
    eventBus.publish(
      MesosStatusUpdateEvent(
        status.getSlaveId.getValue,
        status.getTaskId.getValue,
        status.getState.name,
        if (status.hasMessage) status.getMessage else "",
        taskIdUtil.appId(task.getId),
        task.getHost,
        task.getPortsList.asScala,
        task.getVersion
      )
    )
  }

}
