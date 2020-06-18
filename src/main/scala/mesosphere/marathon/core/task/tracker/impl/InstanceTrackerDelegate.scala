package mesosphere.marathon
package core.task.tracker.impl

import java.time.Clock
import java.util.concurrent.TimeoutException

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import akka.util.Timeout
import akka.{Done, NotUsed}
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdateEffect, InstanceUpdateOperation, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.{ListBySpec, UpdateContext}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerConfig}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AbsolutePathId, Timestamp}
import org.apache.mesos

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
  * Provides a [[InstanceTracker]] interface to [[InstanceTrackerActor]].
  *
  * This is used for the "global" InstanceTracker trait and it is also
  * is used internally in this package to communicate with the InstanceTracker.
  */
private[marathon] class InstanceTrackerDelegate(
    metrics: Metrics,
    clock: Clock,
    config: InstanceTrackerConfig,
    instanceTrackerRef: ActorRef
)(implicit mat: Materializer)
    extends InstanceTracker {

  override def instancesBySpec()(implicit ec: ExecutionContext): Future[InstanceTracker.InstancesBySpec] =
    tasksByAppTimeMetric {
      (instanceTrackerRef ? InstanceTrackerActor.List).mapTo[InstanceTracker.InstancesBySpec].recover {
        case _: AskTimeoutException =>
          throw new TimeoutException(
            s"timeout while calling instancesBySpec() (current value = ${config.internalTaskTrackerRequestTimeout().milliseconds}ms." +
              "If you know what you are doing, you can adjust the timeout " +
              s"with --${config.internalTaskTrackerRequestTimeout.name}."
          )
      }
    }

  // TODO(jdef) support pods when counting launched instances
  override def countActiveSpecInstances(appId: AbsolutePathId): Future[Int] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    specInstances(appId).map(_.count(instance => instance.isActive))
  }

  override def hasSpecInstances(appId: AbsolutePathId)(implicit ec: ExecutionContext): Future[Boolean] =
    specInstances(appId).map(_.nonEmpty)

  override def specInstances(appId: AbsolutePathId, readAfterWrite: Boolean = false)(implicit
      ec: ExecutionContext
  ): Future[Seq[Instance]] = {
    val query = InstanceTrackerActor.ListBySpec(appId)
    if (readAfterWrite) {
      val promise = Promise[Seq[Instance]]
      queue.offer(QueuedQuery(query, promise)).foreach {
        case QueueOfferResult.Enqueued => logger.info(s"Queued query ${query.appId}")
        case QueueOfferResult.Dropped => promise.failure(new RuntimeException(s"Dropped instance query: $query"))
        case QueueOfferResult.Failure(ex) => promise.failure(new RuntimeException(s"Failed to process instance query $query because", ex))
        case QueueOfferResult.QueueClosed =>
          promise.failure(new RuntimeException(s"Failed to process instance query $query because the queue is closed"))
      }
      promise.future
    } else {
      (instanceTrackerRef ? query).mapTo[Seq[Instance]]
    }
  }

  override def instance(taskId: Instance.Id): Future[Option[Instance]] =
    (instanceTrackerRef ? InstanceTrackerActor.Get(taskId)).mapTo[Option[Instance]]

  private[this] val tasksByAppTimeMetric =
    metrics.timer("debug.instance-tracker.resolve-tasks-by-app-duration")

  implicit val instanceTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

  import scala.concurrent.ExecutionContext.Implicits.global

  sealed trait Queued {
    val idString: String
  }
  case class QueuedUpdate(update: UpdateContext, promise: Promise[InstanceUpdateEffect]) extends Queued {
    override val idString: String = update.appId.toString
  }
  case class QueuedQuery(query: ListBySpec, promise: Promise[Seq[Instance]]) extends Queued {
    override val idString: String = query.appId.toString
  }

  /**
    * Important:
    * We use a [[akka.stream.scaladsl.SourceQueue]] to serialize all instance updates *per Instance.Id*. This is important
    * since the way [[InstanceTrackerActor]] is applying/persisting those update to existing Instance state, having two
    * such update operation in parallel will result in later operation overriding the former one.
    *
    * For this we group all [[InstanceUpdateOperation]]s in substreams hashed by [[Instance.Id.idString]] hash.
    * Number of parallel updates for *different Instance.Ids* is controlled via [[InstanceTrackerConfig.internalInstanceTrackerNumParallelUpdates]]
    * parameter.
    */
  // format: OFF
  val queue = Source
    .queue[Queued](config.internalInstanceTrackerUpdateQueueSize(), OverflowStrategy.dropNew)
    .groupBy(config.internalInstanceTrackerNumParallelUpdates(), queued => Math.abs(queued.idString.hashCode) % config.internalInstanceTrackerNumParallelUpdates())
    .mapAsync(1){
      case QueuedUpdate(update, promise) =>
        logger.debug(s"Sending update to instance tracker: ${update.operation.shortString}")
        val effectF = (instanceTrackerRef ? update)
          .mapTo[InstanceUpdateEffect]
          .transform {
            case s @ Success(_) => logger.info(s"Completed processing instance update ${update.operation.shortString}"); s
            case f @ Failure(e: AskTimeoutException) => logger.error(s"Timed out waiting for response for update $update", e); f
            case f @ Failure(t: Throwable) => logger.error(s"An unexpected error occurred during update processing of: $update", t); f
          }
        promise.completeWith(effectF)

        effectF // We already completed the sender promise with the future result (failed or not)
          .transform(_ => Success(Done)) // so here we map the future to a successful one to preserve the stream
      case QueuedQuery(query, promise) =>
        logger.debug(s"Sending query to instance tracker: ${query.appId}")
        val effectF = (instanceTrackerRef ? query)
          .mapTo[Seq[Instance]]
          .transform {
            case s @ Success(_) => logger.info(s"Completed processing instance query ${query.appId}"); s
            case f @ Failure(e: AskTimeoutException) => logger.error(s"Timed out waiting for response for query $query", e); f
            case f @ Failure(t: Throwable) => logger.error(s"An unexpected error occurred during query processing of: $query", t); f
          }
        promise.completeWith(effectF)
        effectF
          .transform(_ => Success(Done))
    }
    .mergeSubstreams
    .toMat(Sink.ignore)(Keep.left)
    .run()

  override def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect] = {
    val deadline = clock.now + instanceTrackerQueryTimeout.duration
    val update = InstanceTrackerActor.UpdateContext(deadline, stateOp)

    val promise = Promise[InstanceUpdateEffect]
    queue.offer(QueuedUpdate(update, promise)).foreach {
      case QueueOfferResult.Enqueued => logger.info(s"Queued ${update.operation.shortString}")
      case QueueOfferResult.Dropped => promise.failure(new RuntimeException(s"Dropped instance update: $update"))
      case QueueOfferResult.Failure(ex) => promise.failure(new RuntimeException(s"Failed to process instance update $update because", ex))
      case QueueOfferResult.QueueClosed => promise.failure(new RuntimeException(s"Failed to process instance update $update because the queue is closed"))
    }
    promise.future
  }
  // format: ON

  override def schedule(instance: Instance): Future[Done] = {
    require(instance.isScheduled, s"Instance ${instance.instanceId} was not in scheduled state but ${instance.state.condition}")

    import scala.concurrent.ExecutionContext.Implicits.global
    process(InstanceUpdateOperation.Schedule(instance)).map(_ => Done)
  }

  override def revert(instance: Instance): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.Revert(instance)).map(_ => Done)
  }

  override def forceExpunge(instanceId: Instance.Id): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.ForceExpunge(instanceId)).map(_ => Done)
  }

  override def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, updateTime)).map(_ => Done)
  }

  override def reservationTimeout(instanceId: Instance.Id): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.ReservationTimeout(instanceId)).map(_ => Done)
  }

  override def setGoal(instanceId: Instance.Id, goal: Goal, reason: GoalChangeReason): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    logger.info(s"adjusting $instanceId to goal $goal ($reason)")
    process(InstanceUpdateOperation.ChangeGoal(instanceId, goal)).map(_ => Done)
  }

  override val instanceUpdates: Source[(InstancesSnapshot, Source[InstanceChange, NotUsed]), NotUsed] = {
    Source
      .actorRef[Any](Int.MaxValue, OverflowStrategy.fail)
      .watchTermination()(Keep.both)
      .mapMaterializedValue {
        case (ref, done) =>
          done.onComplete { _ =>
            instanceTrackerRef.tell(InstanceTrackerActor.Unsubscribe, ref)
          }(ExecutionContexts.callerThread)
          instanceTrackerRef.tell(InstanceTrackerActor.Subscribe, ref)
          NotUsed
      }
      .prefixAndTail(1)
      .map {
        case (Seq(el: InstancesSnapshot), rest) =>
          // The contract is that all messages delivered after the first snapshot message will be instance change
          (el, rest.asInstanceOf[Source[InstanceChange, NotUsed]]): @silent
        case _ =>
          throw new IllegalStateException("First message expected to be an InstancesSnapshot; this is a bug")
      }
  }
}
