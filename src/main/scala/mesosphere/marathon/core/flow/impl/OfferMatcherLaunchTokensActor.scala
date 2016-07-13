package mesosphere.marathon.core.flow.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.core.flow.LaunchTokenConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.bus.TaskChangeObservables
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import org.apache.mesos.Protos.TaskStatus
import rx.lang.scala.{ Observable, Subscription }

import scala.concurrent.duration._

private[flow] object OfferMatcherLaunchTokensActor {
  def props(
    conf: LaunchTokenConfig,
    taskStatusObservables: TaskChangeObservables,
    offerMatcherManager: OfferMatcherManager): Props = {
    Props(new OfferMatcherLaunchTokensActor(conf, taskStatusObservables, offerMatcherManager))
  }
}

/**
  * We throttle task launching to avoid overloading ourself and Mesos.
  *
  * Mesos will acknowledge task launches slower when it is overloaded and faster if it has free capacity.
  * Thus we take that as a signal to allow launching more tasks.
  *
  * In addition, we periodically reset our token count to a fixed number.
  */
private class OfferMatcherLaunchTokensActor(
  conf: LaunchTokenConfig,
  taskStatusObservables: TaskChangeObservables, offerMatcherManager: OfferMatcherManager)
    extends Actor with ActorLogging {
  var taskStatusUpdateSubscription: Subscription = _
  var periodicSetToken: Cancellable = _

  override def preStart(): Unit = {
    val all: Observable[TaskChanged] = taskStatusObservables.forAll
    taskStatusUpdateSubscription = all.subscribe(self ! _)

    import context.dispatcher
    periodicSetToken = context.system.scheduler.schedule(0.seconds, conf.launchTokenRefreshInterval().millis)(
      offerMatcherManager.setLaunchTokens(conf.launchTokens())
    )
  }

  override def postStop(): Unit = {
    taskStatusUpdateSubscription.unsubscribe()
    periodicSetToken.cancel()
  }

  private[this] def healthy(status: TaskStatus): Boolean = !status.hasHealthy || status.getHealthy

  override def receive: Receive = {
    case TaskChanged(TaskStateOp.MesosUpdate(task, MarathonTaskStatus.Running, mesosStatus, timestamp), stateChange)
      if healthy(mesosStatus) =>

      offerMatcherManager.addLaunchTokens(1)
  }
}
