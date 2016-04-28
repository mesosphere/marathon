package mesosphere.marathon.core.jobs

import akka.actor.{ Actor, ActorLogging, Props }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.RunSpec

/**
  * Note this Actor is currently designed to only run exactly one instance of a job.
  */
class JobRunExecutorActor(runSpec: RunSpec, launchQueue: LaunchQueue) extends Actor with ActorLogging {
  import JobRunExecutorActor._

  // FIXME (Jobs): really simple for now
  private[this] var failedAttempts = 0

  override def preStart(): Unit = {
    log.info("Started JobRunExecutor for {}", runSpec)
    launchQueue.add(runSpec, count = 1)

    super.preStart()
  }

  override def receive: Receive = {
    case TaskChanged(stateOp, _) if finished(stateOp) =>
      log.info("Job finished: {}", runSpec.id)
      context.stop(self)

    case TaskChanged(stateOp, _) if terminal(stateOp) =>
      log.info("Job terminated: {}", runSpec.id)
      failedAttempts += 1
      if (failedAttempts < maxFailedAttempts) {
        launchQueue.add(runSpec, count = 1)
      }
      else {
        log.info(s"${runSpec.id} failed $maxFailedAttempts times. Giving up.")
        launchQueue.purge(runSpec.id)
        context.stop(self)
      }

    case taskChanged: TaskChanged =>
      log.info("Ignoring taskChanged")

    case unexpected: Any =>
      log.warning("Received unexpected message {}", unexpected)
  }

  private[this] def finished(stateOp: TaskStateOp): Boolean = stateOp match {
    case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Finished(_), _) => true
    case _ => false
  }

  private[this] def terminal(stateOp: TaskStateOp): Boolean = stateOp match {
    case TaskStateOp.MesosUpdate(_, MarathonTaskStatus.Terminal(_), _) => true
    case _ => false
  }
}

object JobRunExecutorActor {
  private val maxFailedAttempts = 5

  def props(runSpec: RunSpec, launchQueue: LaunchQueue): Props = Props(new JobRunExecutorActor(runSpec, launchQueue))
}
