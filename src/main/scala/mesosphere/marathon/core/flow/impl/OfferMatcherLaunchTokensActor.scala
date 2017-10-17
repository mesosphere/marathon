package mesosphere.marathon
package core.flow.impl

import akka.actor.{ Actor, Cancellable, Props }
import mesosphere.marathon.core.event.InstanceChanged
import mesosphere.marathon.core.flow.LaunchTokenConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager

import scala.concurrent.duration._

private[flow] object OfferMatcherLaunchTokensActor {
  def props(
    conf: LaunchTokenConfig,
    offerMatcherManager: OfferMatcherManager): Props = {
    Props(new OfferMatcherLaunchTokensActor(conf, offerMatcherManager))
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
private[impl] class OfferMatcherLaunchTokensActor(conf: LaunchTokenConfig, offerMatcherManager: OfferMatcherManager)
    extends Actor {
  var periodicSetToken: Cancellable = _

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[InstanceChanged])

    import context.dispatcher
    periodicSetToken = context.system.scheduler.schedule(0.seconds, conf.launchTokenRefreshInterval().millis)(
      offerMatcherManager.setLaunchTokens(conf.launchTokens())
    )
  }

  override def postStop(): Unit = {
    periodicSetToken.cancel()
  }

  override def receive: Receive = {
    case InstanceChanged(_, _, _, _, instance) if instance.isRunning && instance.state.healthy.fold(true)(_ == true) =>
      offerMatcherManager.addLaunchTokens(1)
  }
}
