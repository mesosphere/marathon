package mesosphere.marathon.core.matcher.base

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ Offer, OfferID, TaskInfo }

import scala.concurrent.Future

object OfferMatcher {
  case class TaskOpWithSource(source: TaskOpSource, taskInfo: TaskInfo, marathonTask: MarathonTask) {
    def accept(): Unit = source.taskOpAccepted(taskInfo)
    def reject(reason: String): Unit = source.taskOpRejected(taskInfo, reason)
  }

  /**
    * Reply from an offer matcher to a MatchOffer. If the offer match
    * could not match the offer in any way it should simply leave the tasks
    * collection empty.
    *
    * To increase fairness between matchers, each normal matcher should only launch as
    * few tasks as possible per offer -- usually one. Multiple tasks could be used
    * if the tasks need to be colocated. The OfferMultiplexer tries to summarize suitable
    * matches from multiple offer matches into one response.
    *
    * A MatchedTaskOps reply does not guarantee that these tasks can actually be launched.
    * The launcher of message should setup some kind of timeout mechanism and handle
    * taskOpAccepted/taskOpRejected calls appropriately.
    *
    * @param offerId the identifier of the offer
    * @param tasks the tasks that should be launched on that offer
    * @param resendThisOffer true, if this offer could not be processed completely (e.g. timeout)
    *                        and should be resend and processed again
    */
  case class MatchedTaskOps(offerId: OfferID, tasks: Seq[TaskOpWithSource], resendThisOffer: Boolean = false)

  trait TaskOpSource {
    def taskOpAccepted(taskInfo: TaskInfo)
    def taskOpRejected(taskInfo: TaskInfo, reason: String)
  }
}

/**
  * Tries to match offers with given tasks.
  */
trait OfferMatcher {
  /**
    * Process offer and return the tasks that this matcher wants to launch.
    *
    * The offer matcher can expect either a taskOpAccepted or a taskOpRejected call
    * for every returned `org.apache.mesos.Protos.TaskInfo`.
    */
  def matchOffer(deadline: Timestamp, offer: Offer): Future[OfferMatcher.MatchedTaskOps]
}
