package mesosphere.marathon.core.jobs

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated }
import akka.event.LoggingReceive
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId }

import scala.concurrent.duration._

class JobSchedulerActor(launchQueue: LaunchQueue, appRepo: AppRepository) extends Actor with ActorLogging {

  import JobSchedulerActor._

  private[this] var tick: Cancellable = _

  private[this] var count: Int = 0

  private[this] var executors = Map.empty[PathId, ActorRef]

  override def preStart(): Unit = {
    log.info("Started JobSchedulerActor")

    import context.dispatcher
    tick = context.system.scheduler.schedule(initialDelay, interval, self, Tick)
    super.preStart()
  }

  override def postStop(): Unit = {
    tick.cancel()
    super.postStop()
  }

  override def receive: Receive = LoggingReceive.withLabel("default") {
    case Tick =>
      count += 1
      val spec = defaultJob.copy(id = defaultJob.id / s"run-$count")
      log.info("Received Tick -> scheduling {}", count)
      createExecutor(spec)

    case JobSchedulerActor.ScheduleJob(spec) =>
      log.info("Received ScheduleJob({})", spec)
      createExecutor(spec)
      sender() ! EmptyResponse

    case taskChanged: TaskChanged =>
      log.info("Received TaskChanged for {}", taskChanged.taskId)
      notifyExecutor(taskChanged)
      sender() ! EmptyResponse

    case Terminated(actorRef) =>
      log.info("Received Terminated({})", actorRef)
      removeExecutor(actorRef)

    case unexpected: Any =>
      log.warning("Received unexpected message {}", unexpected)
      sender() ! EmptyResponse
  }

  // FIXME (Jobs): should be RunSpec but we can't store a RunSpec in the AppRepo
  // FIXME (Jobs): SchedulerActions will kill a task during reconciliation if the app is not stored
  private[this] def createExecutor(runSpec: AppDefinition): Unit = {
    import context.dispatcher
    appRepo.store(runSpec).map { _ =>
      // FIXME (Jobs): make sure the name or runSpec.id is unique
      val executorRef = context.actorOf(JobRunExecutorActor.props(runSpec, launchQueue), runSpec.id.safePath)
      context.watch(executorRef)
      executors += runSpec.id -> executorRef
      log.info("Created {} and added to state", executorRef)
    }
  }

  private[this] def removeExecutor(actorRef: ActorRef): Unit = {
    // FIXME (Jobs): this is not safe but easy for now
    val id = PathId.fromSafePath(actorRef.path.name)
    log.info("About to remove {}", id)

    import context.dispatcher
    appRepo.expunge(id).map { _ =>
      executors.get(id).foreach(context.unwatch)
      executors -= id
      log.info("Removed {} from state", actorRef)
    }
  }

  private[this] def notifyExecutor(taskChanged: TaskChanged): Unit = {
    val runSpecId = taskChanged.taskId.runSpecId
    executors.get(runSpecId).foreach(_ ! taskChanged)
  }

}

object JobSchedulerActor {

  def props(launchQueue: LaunchQueue, appRepo: AppRepository): Props =
    Props(new JobSchedulerActor(launchQueue, appRepo))

  // dummy implementation helpers

  private val initialDelay = 5.seconds
  private val interval = 5.seconds
  private val defaultJob = AppDefinition(
    id = PathId("/job"),
    cmd = Some("sleep 1 && echo finished && exit 0")
  )

  private val EmptyResponse: Unit = ()

  // Available Messages

  case class ScheduleJob(spec: AppDefinition)

  case object Tick

}
