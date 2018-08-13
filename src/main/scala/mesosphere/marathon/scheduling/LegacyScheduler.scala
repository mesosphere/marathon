package mesosphere.marathon
package scheduling

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.state.{PathId, RunSpec}
import org.apache.mesos.Protos

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

case class LegacyScheduler(
    offerProcessor: OfferProcessor,
    instanceTracker: InstanceTracker,
    statusUpdateProcessor: TaskStatusUpdateProcessor,
    killService: KillService,
    launchQueue: LaunchQueue) extends Scheduler with StrictLogging {

  override def schedule(runSpec: RunSpec, count: Int)(implicit ec: ExecutionContext): Future[Done] = async {
    // Let launch queue manage task launcher actor.
    await(launchQueue.sync(runSpec))

    val instancesToSchedule = 0.until(count).map { _ => Instance.scheduled(runSpec, Instance.Id.forRunSpec(runSpec.id)) }
    await(instanceTracker.schedule(instancesToSchedule))

    logger.info(s"Scheduled ${instancesToSchedule.map(_.instanceId)}")
    Done
  }

  override def reschedule(instance: Instance, runSpec: RunSpec)(implicit ec: ExecutionContext): Future[Done] =
    async {
      // Let launch queue manage task launcher actor.
      await(launchQueue.sync(runSpec))

      /* This method is actually and update from Goal.Stopped to Goal.Running. However, only instances with reservations
         support such an update. MARATHON-8373 will introduce incarnations for ephemeral instances and enable rescheduling
         support for all instances.
       */
      assert(instance.isReserved && instance.state.goal == Goal.Stopped)
      await(instanceTracker.process(RescheduleReserved(instance, runSpec.version)))

      logger.info(s"Rescheduled ${instance.instanceId}")
      Done
    }

  // TODO(karsten): Investigate how we can drop this method as it leaks implementation details of the scheduler.
  override def resetDelay(spec: RunSpec): Unit = launchQueue.resetDelay(spec)

  /* The sync is responsible for two things:
     1. Tell the [[LaunchQueueActor]] to start a [[TaskLauncherActor]].
     2. Tell the [[TaskLauncherActor]] about the new run spec version to start.

     Once we attach the run spec to an instance we can drop 2. This is addressed in MARATHON-8325.
     Once the [[TaskLauncherActor]] is just a pure function called un [[OfferMatcherManagerActor]] we can drop 1. and
     thus this method altogether. See MARATHON-8444 for that.

     Overall this method should not be part of the future interface as it leaks implementation details of the scheduler
     internals.
   */
  override def sync(spec: RunSpec)(implicit ec: ExecutionContext): Future[Done] = launchQueue.sync(spec)

  override def getInstances(runSpecId: PathId)(implicit ec: ExecutionContext): Future[Seq[Instance]] =
    instanceTracker.specInstances(runSpecId)

  override def getInstance(instanceId: Instance.Id)(implicit ec: ExecutionContext): Future[Option[Instance]] =
    instanceTracker.get(instanceId)

  @SuppressWarnings(Array("all")) // async/await
  override def run(instances: Seq[Instance])(implicit ec: ExecutionContext): Future[Done] =
    async {
      val work = Future.sequence(instances.map { i => instanceTracker.setGoal(i.instanceId, Goal.Running) })
      await(work)
      Done
    }

  @SuppressWarnings(Array("all")) // async/await
  override def decommission(instances: Seq[Instance], killReason: KillReason)(implicit ec: ExecutionContext): Future[Done] =
    async {
      val work = Future.sequence(instances.map { i => instanceTracker.setGoal(i.instanceId, Goal.Decommissioned) })
      await(work)

      await(killService.killInstances(instances, killReason))
    }

  @SuppressWarnings(Array("all")) // async/await
  override def stop(instances: Seq[Instance], killReason: KillReason)(implicit ec: ExecutionContext): Future[Done] =
    async {
      val work = Future.sequence(instances.map { i => instanceTracker.setGoal(i.instanceId, Goal.Stopped) })
      await(work)

      await(killService.killInstances(instances, killReason))
    }

  override def processOffer(offer: Protos.Offer): Future[Done] = offerProcessor.processOffer(offer)

  override def processMesosUpdate(status: Protos.TaskStatus)(implicit ec: ExecutionContext): Future[Done] = statusUpdateProcessor.publish(status).map(_ => Done)
}
