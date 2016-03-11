package mesosphere.marathon.core.matcher.base

import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.{ Protos => Mesos }

import scala.concurrent.Future

object OfferMatcher {

  /**
    * A TaskOp with a [[TaskOpSource]].
    *
    * The [[TaskOpSource]] is informed whether the op is ultimately send to Mesos or if it is rejected
    * (e.g. by throttling logic).
    */
  case class TaskOpWithSource(source: TaskOpSource, op: TaskOp) {
    def taskId: Task.Id = op.taskId
    def accept(): Unit = source.taskOpAccepted(op)
    def reject(reason: String): Unit = source.taskOpRejected(op, reason)
  }

  /**
    * Reply from an offer matcher to a MatchOffer. If the offer match
    * could not match the offer in any way it should simply leave the tasks
    * collection empty.
    *
    * To increase fairness between matchers, each normal matcher should schedule as few operations
    * as possible per offer per match, e.g. one for task launches without reservations. Multiple launches could be used
    * if the tasks need to be colocated or if the operations are intrinsically dependent on each other.
    * The OfferMultiplexer tries to summarize suitable
    * matches from multiple offer matches into one response.
    *
    * A MatchedTaskOps reply does not guarantee that these operations can actually be executed.
    * The launcher of message should setup some kind of timeout mechanism and handle
    * taskOpAccepted/taskOpRejected calls appropriately.
    *
    * @param offerId the identifier of the offer
    * @param opsWithSource the ops that should be executed on that offer including the source of each op
    * @param resendThisOffer true, if this offer could not be processed completely (e.g. timeout)
    *                        and should be resend and processed again
    */
  case class MatchedTaskOps(
      offerId: Mesos.OfferID,
      opsWithSource: Seq[TaskOpWithSource],
      resendThisOffer: Boolean = false) {

    /** all included [TaskOp] without the source information. */
    def ops: Iterable[TaskOp] = opsWithSource.view.map(_.op)

    /** All TaskInfos of launched tasks. */
    def launchedTaskInfos: Iterable[Mesos.TaskInfo] = ops.view.collect {
      case TaskOp.Launch(taskInfo, _, _, _) => taskInfo
    }
  }

  object MatchedTaskOps {
    def noMatch(offerId: Mesos.OfferID, resendThisOffer: Boolean = false): MatchedTaskOps =
      new MatchedTaskOps(offerId, Seq.empty, resendThisOffer = resendThisOffer)
  }

  trait TaskOpSource {
    def taskOpAccepted(taskOp: TaskOp)
    def taskOpRejected(taskOp: TaskOp, reason: String)
  }
}

/**
  * Tries to match offers with given tasks.
  */
trait OfferMatcher {
  /**
    * Process offer and return the ops that this matcher wants to execute on this offer.
    *
    * The offer matcher can expect either a taskOpAccepted or a taskOpRejected call
    * for every returned `org.apache.mesos.Protos.TaskInfo`.
    */
  def matchOffer(deadline: Timestamp, offer: Mesos.Offer): Future[OfferMatcher.MatchedTaskOps]

  /**
    * We can optimize the offer routing for different offer matcher in case there are reserved resources.
    * A defined precedence is used to filter incoming offers with reservations that apply to this filter.
    * If the filter matches, the offer matcher manager has higher priority than other matchers.
    */
  def precedenceFor: Option[PathId] = None
}
