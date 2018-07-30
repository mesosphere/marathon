package mesosphere.marathon
package core.task.jobs.impl

import java.time.{Clock, Instant}
import java.util.UUID

import akka.{Done, NotUsed}
import akka.actor._
import akka.event.EventStream
import akka.stream.{ActorMaterializer, ClosedShape, Graph, OverflowStrategy}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.ReconciliationStatusUpdate
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.{Instance, Reservation, TestInstanceBuilder, TestTaskBuilder}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

class OverdueTasksActorTest extends AkkaUnitTest with Eventually {

  /**
    * Utility class that re-shapes a Source[T,K] to a Source[T, Cancellable],
    * used to mock the tick arguments of overdueTasksGraph constructor.
    */
  object Cancelled extends Cancellable {
    override def cancel(): Boolean = true
    override def isCancelled: Boolean = true

    def shapeAsCancellable[T, K](src: Source[T, K]): Source[T, Cancellable] =
      if (src == null) null
      else src.mapMaterializedValue(_ => Cancelled)
  }

  /**
    * The test fixture
    */
  case class Fixture(
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      driver: SchedulerDriver = mock[SchedulerDriver],
      killService: KillService = mock[KillService],
      marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = mock[MarathonSchedulerDriverHolder],
      clock: SettableClock = new SettableClock()) {

    val driverHolder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)

    val config: AllConf = MarathonTestHelper.defaultConfig()
    val support: OverdueTasksActor.Support = new OverdueTasksActor.Support(
      config,
      instanceTracker,
      killService,
      marathonSchedulerDriverHolder,
      clock
    )
    val eventStream: EventStream = system.eventStream

    /**
      * Stream and function to send ticks on the checkTick source
      */
    private[this] val checkTickActor = Source.actorRef[Instant](1000, OverflowStrategy.dropNew)
    private[this] val (checkTickListener, checkTickSource) = checkTickActor.preMaterialize()
    def sendCheckTick(now: Instant): Unit = {
      checkTickListener ! now
    }

    /**
      * Stream and function to send ticks on the reconcileTick source
      */
    private[this] val reconcileTickActor = Source.actorRef[Instant](1000, OverflowStrategy.dropNew)
    private[this] val (reconcileTickListener, reconcileTickSource) = reconcileTickActor.preMaterialize()
    def sendReconcileTick(now: Instant): Unit = {
      reconcileTickListener ! now
    }

    /**
      * Stream and function to send reconciliation task updates on the reconciliationStatusUpdates source
      */
    private[this] val reconciliationActor = Source.actorRef[ReconciliationStatusUpdate](1000, OverflowStrategy.dropNew)
    private[this] val (reconciliationListener, reconciliationStatusUpdates) = reconciliationActor.preMaterialize()
    def sendReconciliationStatusUpdate(status: ReconciliationStatusUpdate): Unit = {
      reconciliationListener ! status
    }

    private[this] val graph: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(
      OverdueTasksActor.overdueTasksGraph(
        support,
        Cancelled.shapeAsCancellable(checkTickSource),
        Cancelled.shapeAsCancellable(reconcileTickSource),
        10,
        100,
        reconciliationStatusUpdates
      )
    )

    private[this] val materializerParent = system.actorOf(Props(new Actor {
      private val graphMat = ActorMaterializer()(context)
      graph.run()(graphMat)
      override def receive: Receive = Actor.ignoringBehavior
    }))

    def verifyClean(): Unit = {
      def waitForActorProcessingAllAndDying(): Unit = {
        // Stop the checkTickActor
        val probe = TestProbe()
        probe.watch(checkTickListener)
        checkTickListener ! PoisonPill
        probe.expectTerminated(checkTickListener)

        // Stop the reconcileTickActor
        probe.watch(reconcileTickListener)
        reconcileTickListener ! PoisonPill
        probe.expectTerminated(reconcileTickListener)

        // Stop the reconciliationActor
        probe.watch(reconciliationListener)
        reconciliationListener ! PoisonPill
        probe.expectTerminated(reconciliationListener)

        // Stop the graph
        probe.watch(materializerParent)
        materializerParent ! PoisonPill
        probe.expectTerminated(materializerParent)
      }

      waitForActorProcessingAllAndDying()

      noMoreInteractions(instanceTracker)
      noMoreInteractions(driver)
    }
  }

  "OverdueTasksActor" should {

    "no overdue tasks" in new Fixture {
      Given("no tasks")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.empty)

      When("a check tick is sent")
      sendCheckTick(Instant.now(clock))

      Then("nothing should happen")
      verifyClean()
    }

    //    "some overdue tasks" in new Fixture {
    //      Given("one overdue task")
    //      val appId = PathId("/some")
    //      val mockInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
    //      val app = InstanceTracker.InstancesBySpec.forInstances(mockInstance)
    //      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
    //
    //      When("after a check tick")
    //      sendCheckTick(Instant.now(clock))
    //
    //      Then("the task should be reconciled")
    //      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
    //      val tasksStatuses = mockInstance.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
    //      verify(driver, Mockito.timeout(1000)).reconcileTasks(tasksStatuses.asJava)
    //      verifyClean()
    //    }

    //    "no overdue tasks" in new Fixture {
    //      Given("no tasks")
    //      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.empty)
    //
    //      When("a check is performed")
    //      checkActor
    //
    //      Thread.sleep(10000)
    //
    //      Then("eventually list was called")
    //      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])
    //      And("no kill calls are issued")
    //      noMoreInteractions(driver)
    //      verifyClean()
    //    }
    //
    //    "some overdue tasks" in new Fixture {
    //      Given("one overdue task")
    //      val appId = PathId("/some")
    //      val mockInstance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
    //      val app = InstanceTracker.InstancesBySpec.forInstances(mockInstance)
    //      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
    //
    //      When("the check is initiated")
    //      checkActor ! OverdueTasksActor.Check(maybeAck = None)
    //
    //      Then("the task kill gets initiated")
    //      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
    //      verify(killService, Mockito.timeout(1000)).killInstance(mockInstance, KillReason.Overdue)
    //      verifyClean()
    //    }
    //
    //    // sounds strange, but this is how it currently works: determineOverdueTasks will consider a missing startedAt to
    //    // determine whether a task is in staging and might need to be killed if it exceeded the taskLaunchTimeout
    //    "ensure that check kills tasks disregarding the stagedAt property" in new Fixture {
    //      val now = clock.now()
    //
    //      val appId = PathId("/ignored")
    //      val overdueUnstagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
    //      assert(overdueUnstagedTask.tasksMap.valuesIterator.forall(_.status.startedAt.isEmpty))
    //
    //      val unconfirmedNotOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis).getInstance()
    //
    //      val unconfirmedOverdueTask = TestInstanceBuilder.newBuilder(appId).addTaskStarting(now - config.taskLaunchConfirmTimeout().millis - 1.millis).getInstance()
    //
    //      val overdueStagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.days).getInstance()
    //
    //      val stagedTask = TestInstanceBuilder.newBuilder(appId).addTaskStaged(now - 10.seconds).getInstance()
    //
    //      val runningTask = TestInstanceBuilder.newBuilder(appId).addTaskRunning(stagedAt = now - 5.seconds, startedAt = now - 2.seconds).getInstance()
    //
    //      Given("Several somehow overdue tasks plus some not overdue tasks")
    //      val app = InstanceTracker.InstancesBySpec.forInstances(
    //        unconfirmedOverdueTask,
    //        unconfirmedNotOverdueTask,
    //        overdueUnstagedTask,
    //        overdueStagedTask,
    //        stagedTask,
    //        runningTask
    //      )
    //      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
    //
    //      When("We check which tasks should be killed because they're not yet staged or unconfirmed")
    //      val testProbe = TestProbe()
    //      testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
    //      testProbe.expectMsg(3.seconds, ())
    //
    //      Then("The task tracker gets queried")
    //      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])
    //
    //      And("All somehow overdue tasks are killed")
    //      verify(killService).killInstance(unconfirmedOverdueTask, KillReason.Overdue)
    //      verify(killService).killInstance(overdueUnstagedTask, KillReason.Overdue)
    //      verify(killService).killInstance(overdueStagedTask, KillReason.Overdue)
    //
    //      And("but not more")
    //      verifyNoMoreInteractions(driver)
    //      verifyClean()
    //    }
    //
    //    "reservations with a timeout in the past are processed" in new Fixture {
    //      Given("one overdue reservation")
    //      val appId = PathId("/test")
    //      val overdueReserved = reservedWithTimeout(appId, deadline = clock.now() - 1.second)
    //      val recentReserved = reservedWithTimeout(appId, deadline = clock.now() + 1.second)
    //      val app = InstanceTracker.InstancesBySpec.forInstances(recentReserved, overdueReserved)
    //      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
    //      instanceTracker.reservationTimeout(overdueReserved.instanceId) returns Future.successful(Done)
    //
    //      When("the check is initiated")
    //      val testProbe = TestProbe()
    //      testProbe.send(checkActor, OverdueTasksActor.Check(maybeAck = Some(testProbe.ref)))
    //      testProbe.expectMsg(3.seconds, ())
    //
    //      Then("the reservation gets processed")
    //      verify(instanceTracker).instancesBySpec()(any[ExecutionContext])
    //      verify(instanceTracker).reservationTimeout(overdueReserved.instanceId)
    //      verifyClean()
    //    }

  }

  class ReconciliationTrackerFixture(maxReconciliations: Int) {
    val (instancesProbe, _instances: Source[Instance, NotUsed]) = TestSource.probe[Instance].preMaterialize()
    val (statusUpdatesProbe, _statusUpdates) = TestSource.probe[ReconciliationStatusUpdate].preMaterialize()
    val (reconciliationTickProbe, _reconciliationTick) = TestSource.probe[Instant].preMaterialize()

    val (taskKillerProbe, _taskKiller) = TestSink.probe[Instance].preMaterialize()

    private var instanceCount = 0

    private def nextInstanceNr(): Int = {
      instanceCount += 1
      instanceCount
    }

    def newInstance(): Instance = {
      val empty = MarathonTestHelper.emptyInstance()
      val newInstanceId = empty.instanceId.copy(runSpecId = PathId(s"${empty.runSpecId}-${nextInstanceNr()}"))
      val task = TestTaskBuilder.Helper.minimalTask(Task.Id.forInstanceId(newInstanceId, None))
      empty.copy(instanceId = newInstanceId, tasksMap = Map(task.taskId -> task))
    }

    val tracker = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val reconciliationTracker = builder.add(
        new ReconciliationTracker(_ => Future.successful(Done), 2, maxReconciliations)
      )

      val i = builder.add(_instances)
      val su = builder.add(_statusUpdates)
      val t = builder.add(_reconciliationTick)
      val tk = builder.add(_taskKiller)

      i ~> reconciliationTracker.in0
      su ~> reconciliationTracker.in1
      t ~> reconciliationTracker.in2

      reconciliationTracker.out ~> tk

      ClosedShape
    })
  }

  "ReconciliationTracker" should {
    "send instances to kill once exceeded maxReconciliations" in new ReconciliationTrackerFixture(maxReconciliations = 2) {
      tracker.run()

      val instance = newInstance()

      taskKillerProbe.request(1)

      instancesProbe.sendNext(instance)
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNext(instance)

    }

    "stop tracking instances once a proper status update arrives" in new ReconciliationTrackerFixture(1) {
      tracker.run()

      val instance = newInstance()

      taskKillerProbe.request(1)

      instancesProbe.sendNext(instance)

      statusUpdatesProbe.sendNext(ReconciliationStatusUpdate(taskId = instance.tasksMap.head._1, Condition.Running))

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)

    }

    "reset reconciliation attempt count if staging status is confirmed" in new ReconciliationTrackerFixture(2) {
      tracker.run()

      val instance = newInstance()

      taskKillerProbe.request(1)

      instancesProbe.sendNext(instance)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)

      statusUpdatesProbe.sendNext(ReconciliationStatusUpdate(taskId = instance.tasksMap.head._1, Condition.Staging))
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNext(instance)
    }


  }

  private[this] def reservedWithTimeout(appId: PathId, deadline: Timestamp): Instance = {
    val state = Reservation.State.New(timeout = Some(Reservation.Timeout(
      initiated = Timestamp.zero,
      deadline = deadline,
      reason = Reservation.Timeout.Reason.ReservationTimeout)))
    TestInstanceBuilder.newBuilder(appId).addTaskResidentReserved(state).getInstance()
  }
}
