package mesosphere.marathon
package core.task.jobs.impl

import java.time.{Clock, Instant}

import akka.{Done, NotUsed}
import akka.actor._
import akka.event.EventStream
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source}
import akka.stream.stage._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.ReconciliationStatusUpdate
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.Timestamp

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.collection.JavaConverters._

private[jobs] object OverdueTasksActor {
  def props(
    config: MarathonConf,
    instanceTracker: InstanceTracker,
    killService: KillService,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    clock: Clock): Props = {
    Props(new OverdueTasksActor(new Support(config, instanceTracker, killService, marathonSchedulerDriverHolder, clock)))
  }

  /**
    * Contains the core logic for the KillOverdueTasksActor.
    */
  private class Support(
      config: MarathonConf,
      val instanceTracker: InstanceTracker,
      val killService: KillService,
      val marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
      val clock: Clock) extends StrictLogging {
    import scala.concurrent.ExecutionContext.Implicits.global

    def check(): Future[Unit] = {
      val now = clock.now()
      logger.debug("Checking for overdue tasks")
      instanceTracker.instancesBySpec().flatMap { tasksByApp =>
        val instances = tasksByApp.allInstances

        killOverdueInstances(now, instances)

        timeoutOverdueReservations(now, instances)
      }
    }

    private[this] def killOverdueInstances(now: Timestamp, instances: Seq[Instance]): Unit = {
      overdueTasks(now, instances).foreach { overdueTask =>
        logger.info(s"Killing overdue ${overdueTask.instanceId}")
        killService.killInstance(overdueTask, KillReason.Overdue)
      }
    }

    private[impl] def overdueTasks(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      // stagedAt is set when the task is created by the scheduler
      val stagedExpire = now - config.taskLaunchTimeout().millis
      val unconfirmedExpire = now - config.taskLaunchConfirmTimeout().millis

      def launchedAndExpired(task: Task): Boolean = {
        task.status.condition match {
          case Condition.Created | Condition.Starting if task.status.stagedAt < unconfirmedExpire =>
            logger.warn(s"Should kill: ${task.taskId} was launched " +
              s"${task.status.stagedAt.until(now).toSeconds}s ago and was not confirmed yet")
            true

          case Condition.Staging if task.status.stagedAt < stagedExpire =>
            logger.warn(s"Should kill: ${task.taskId} was staged ${task.status.stagedAt.until(now).toSeconds}s" +
              " ago and has not yet started")
            true

          case _ =>
            // running
            false
        }
      }

      // TODO(PODS): adjust this to consider instance.status and `since`
      instances.filter(instance => instance.tasksMap.valuesIterator.exists(launchedAndExpired))
    }

    private[impl] def timeoutOverdueReservations(now: Timestamp, instances: Seq[Instance]): Future[Unit] = {
      val taskTimeoutResults = overdueReservations(now, instances).map { instance =>
        logger.warn("Scheduling ReservationTimeout for {}", instance.instanceId)
        instanceTracker.reservationTimeout(instance.instanceId)
      }
      Future.sequence(taskTimeoutResults).map(_ => ())
    }

    private[this] def overdueReservations(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      instances.filter { instance =>
        instance.isReserved && instance.reservation.exists(_.state.timeout.exists(_.deadline <= now))
      }
    }
  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])

  private[impl] def overdueTasksGraph(
    support: Support,
    tick: Source[NotUsed, Cancellable],
    reconcileTasks: Seq[Task] => Future[Done],
    eventStream: EventStream)(implicit ec: ExecutionContext, mat: Materializer): Graph[ClosedShape, NotUsed] =
    GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      def now() = support.clock.now()

      val actorSource = Source.actorRef[ReconciliationStatusUpdate](1000, OverflowStrategy.dropNew)
      val (actorListener, reconciliationStatusUpdates) = actorSource.preMaterialize()
      eventStream.subscribe(actorListener, classOf[ReconciliationStatusUpdate])
      val instancesBySpec = Flow[NotUsed]
        .mapAsync(1) { t =>
          support.instanceTracker.instancesBySpec()
        }.map { instanceBs =>
          instanceBs.allInstances
        }
      val timeoutOverdueReservations = Flow[Seq[Instance]]
        .mapAsync(1)(instances => support.timeoutOverdueReservations(now(), instances))
        .to(Sink.ignore)
      val overdueInstancesFilter: Flow[Seq[Instance], Instance, NotUsed] = Flow[Seq[Instance]]
        .mapConcat(instances => support.overdueTasks(now(), instances))
      val reconciliationTracker = builder.add(new ReconciliationTracker(reconcileTasks, 100, 3, 60.seconds))
      val killTasks: Sink[Instance, NotUsed] = Flow[Instance]
        .mapAsync(1)(instance => support.killService.killInstance(instance, KillReason.Overdue))
        .to(Sink.ignore)

      val bcast = builder.add(Broadcast[Seq[Instance]](2))

    //format: OFF
                                bcast ~> timeoutOverdueReservations
    tick ~> instancesBySpec ~>  bcast ~> overdueInstancesFilter ~> reconciliationTracker.in0
    reconciliationStatusUpdates       ~>                           reconciliationTracker.in1

    reconciliationTracker.out ~> killTasks

      //format: ON

      ClosedShape
    }
}

private class OverdueTasksActor(support: OverdueTasksActor.Support) extends Actor with StrictLogging {

  implicit val mat = ActorMaterializer()

  import context.dispatcher

  override def preStart(): Unit = {
    g.run()
  }

  def reconcileTasks(tasks: Seq[Task]): Future[Done] = Future {
    support.marathonSchedulerDriverHolder.driver.map { d =>
      blocking {
        d.reconcileTasks(tasks.flatMap(_.status.mesosStatus).asJava)
      }
    }
    Done
  } (context.system.dispatchers.lookup("marathon-blocking-dispatcher"))

  val g = RunnableGraph.fromGraph(OverdueTasksActor.overdueTasksGraph(support, Source.tick(60.seconds, 60.seconds, NotUsed), reconcileTasks, context.system.eventStream))

  override def receive: Receive = {
    case msg => logger.info(s"unexpected message $msg")
    //    case OverdueTasksActor.Check(maybeAck) =>
    //      val resultFuture = support.check()
    //      maybeAck match {
    //        case Some(ack) =>
    //          import akka.pattern.pipe
    //          import context.dispatcher
    //          resultFuture.pipeTo(ack)
    //
    //        case None =>
    //          import context.dispatcher
    //          resultFuture.failed.foreach { case NonFatal(e) => logger.warn("error while checking for overdue tasks", e) }
    //      }
  }
}

class ReconciliationTracker(
    reconcileTasks: Seq[Task] => Future[Done],
    bufferSize: Int,
    maxReconciliations: Int,
    reconciliationInterval: FiniteDuration) extends GraphStage[FanInShape3[Instance, ReconciliationStatusUpdate, Instant, Instance]] {

  case object ReconciliationTimer

  val instanceIn = Inlet[Instance]("ReconciliationTracker.instanceIn")
  val statusUpdateIn = Inlet[ReconciliationStatusUpdate]("ReconciliationTracker.statusUpdateIn")
  val tickIn = Inlet[Instant]("ReconciliationTracker.tickIn")
  val out = Outlet[Instance]("ReconciliationTracker.out")

  override def shape: FanInShape3[Instance, ReconciliationStatusUpdate, Instant, Instance] = new FanInShape3(instanceIn, statusUpdateIn, tickIn, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var pendingReconciliations: Map[Instance.Id, (Instance, Int)] = Map.empty

//      override def preStart(): Unit = {
//        schedulePeriodicallyWithInitialDelay(ReconciliationTimer, reconciliationInterval, reconciliationInterval)
//      }

      setHandler(instanceIn, new InHandler {
        override def onPush(): Unit = {
          val instance = grab(instanceIn)
          if (!pendingReconciliations.contains(instance.instanceId)) {
            pendingReconciliations += ((instance.instanceId, (instance, 0)))
            if (pendingReconciliations.size < bufferSize) {
              pull(instanceIn) //request new element if the buffer is not full
            }
          } else {
            pull(instanceIn)
          }
        }
      })

      setHandler(statusUpdateIn, new InHandler {
        override def onPush(): Unit = {
          val ReconciliationStatusUpdate(taskId, taskStatus) = grab(statusUpdateIn)
          taskStatus match {
            case Condition.Created | Condition.Starting | Condition.Staging =>
            /*
              This status means that nothing started yet, so we continue to wait
             */
            case _ => //Status changed, let's remove the instance from the tracher
              val maybeInstanceId = pendingReconciliations.valuesIterator.find(_._1.tasksMap.contains(taskId)).map(_._1.instanceId)
              maybeInstanceId.foreach { instanceId =>
                pendingReconciliations -= instanceId
              }
          }
          pull(statusUpdateIn)
        }
      })

      setHandler(tickIn, new InHandler {
        override def onPush(): Unit = {
          val now: Instant = grab(tickIn)
          val tasksStatuses = pendingReconciliations.valuesIterator.flatMap(_._1.tasksMap.valuesIterator).toList
          reconcileTasks(tasksStatuses)
          pendingReconciliations = pendingReconciliations.mapValues { case (instanceId, count) => (instanceId, count + 1) }
          pull(tickIn)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (pendingReconciliations.nonEmpty) {
            emitMultiple(out, pendingReconciliations.valuesIterator.filter(_._2 > maxReconciliations).map(_._1).toList)
          } else {
            pull(instanceIn)
          }
        }
      })

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case ReconciliationTimer =>

        }
      }

    }

}