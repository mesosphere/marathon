package mesosphere.marathon
package core.launchqueue

import akka.Done
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.launchqueue.impl.RateLimiter.DelayUpdate
import mesosphere.marathon.state.{RunSpec, RunSpecConfigRef}

import scala.concurrent.Future

/**
  * The LaunchQueue contains requests to launch new instances for a run spec. For every method returning T
  * there is a corresponding async method which returns a Future[T]. Async methods should be preferred
  * where synchronous methods will be deprecated and gradually removed.
  */
trait LaunchQueue {

  /** Request to launch `count` additional instances conforming to the given run spec. */
  def add(spec: RunSpec, count: Int = 1): Future[Done]

  /** Get delay (if any) on the given RunnableSpec */
  def getDelay(specConfigRef: RunSpecConfigRef): Future[DelayUpdate]

  /** Add delay to the given RunnableSpec because of a failed instance */
  def addDelay(spec: RunSpec): Unit

  /** Reset the backoff delay for the given RunnableSpec. */
  def resetDelay(spec: RunSpec): Unit

  /** Advance the reference time point of the delay for the given RunSpec */
  def advanceDelay(spec: RunSpec): Unit

  /** Notify queue about InstanceUpdate */
  def notifyOfInstanceUpdate(update: InstanceChange): Future[Done]
}
