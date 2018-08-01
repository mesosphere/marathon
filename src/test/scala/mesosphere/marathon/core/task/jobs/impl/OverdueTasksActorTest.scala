package mesosphere.marathon
package core.task.jobs.impl

import java.time.{Clock, Instant}
import java.util

import akka.{Done, NotUsed}
import akka.actor._
import akka.event.EventStream
import akka.stream.{ActorMaterializer, ClosedShape, Graph, OverflowStrategy}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Source}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.{Running, Staging, Starting}
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
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.concurrent.Eventually
import org.apache.mesos.Protos

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
      maxReconciliations: Int = 3
    ) {

    val driver: SchedulerDriver = mock[SchedulerDriver]
    val driverHolder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)

    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    val killService: KillService = mock[KillService]
    val clock: SettableClock = new SettableClock()

    val config: AllConf = MarathonTestHelper.defaultConfig()
    val support: OverdueTasksActor.Support = new OverdueTasksActor.Support(
      config,
      instanceTracker,
      killService,
      driverHolder,
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
        maxReconciliations,
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

    "work without tasks" in new Fixture {
      Given("no tasks")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.empty)

      When("a check tick is sent")
      sendCheckTick(Instant.now(clock))

      Then("nothing should happen")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      noMoreInteractions(killService)
      verifyClean()
    }

    "reconcile some overdue tasks" in new Fixture {
      val appId = PathId("/some")
      val mockInstance = TestInstanceBuilder.newBuilder(appId)
        .addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(mockInstance)

      Given("one overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and a reconcile tick are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers reconciliation
      Thread.sleep(100)

      Then("the task should be reconciled")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = mockInstance.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000)).reconcileTasks(tasksStatuses.asJava)
      noMoreInteractions(killService)
      verifyClean()
    }

    "reconcile the tasks enough times before killing" in new Fixture {
      val appId = PathId("/some")
      val mockInstance = TestInstanceBuilder.newBuilder(appId)
        .addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(mockInstance)

      Given("one overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and 3 reconciliation ticks are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers 3 reconciliations
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      Then("the task should be reconciled 3 times without killing")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = mockInstance.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(3)).reconcileTasks(tasksStatuses.asJava)
      noMoreInteractions(killService)
      verifyClean()
    }

    "reconcile the tasks enough times and kills it" in new Fixture {
      val appId = PathId("/some")
      val mockInstance = TestInstanceBuilder.newBuilder(appId)
        .addTaskStaged(version = Some(Timestamp(1)), stagedAt = Timestamp(2)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(mockInstance)

      Given("one overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and 4 reconciliation ticks are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers 3 reconciliations
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock)) // This should expunge the task
      Thread.sleep(100)

      Then("the task should be reconciled 3 times and then killed")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = mockInstance.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(3)).reconcileTasks(tasksStatuses.asJava)
      verify(killService).killInstance(mockInstance, KillReason.Overdue)
      verifyClean()
    }

    "reconcile only overdue tasks" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueInstance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      val nonOverdueInstance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(now)).getInstance()

      val app = InstanceTracker.InstancesBySpec.forInstances(
        overdueInstance,
        nonOverdueInstance
      )

      Given("an overdue and a non-overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and a reconcile tick are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers reconciliation
      Thread.sleep(100)

      Then("only the overdue task should be reconciled")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = overdueInstance.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(1)).reconcileTasks(tasksStatuses.asJava)
      noMoreInteractions(killService)
      verifyClean()
    }

    "time out overdue reservations" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueReserved = reservedWithTimeout(appId, deadline = clock.now() -  1.second)
      val recentReserved = reservedWithTimeout(appId, deadline = clock.now() + 1.second)
      val app = InstanceTracker.InstancesBySpec.forInstances(
        overdueReserved,
        recentReserved
      )

      Given("a task with overdue and a task with non-overdue reservation")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)
      instanceTracker.reservationTimeout(overdueReserved.instanceId) returns Future.successful(Done)

      When("a check tick is sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)

      Then("only the overdue task should be timed out")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      verify(instanceTracker, Mockito.timeout(1000)).reservationTimeout(overdueReserved.instanceId)
      noMoreInteractions(driver)
      noMoreInteractions(killService)
      verifyClean()
    }

    "reconcile overdue tasks in Created, Starting and Staging states" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueCreating = TestInstanceBuilder.newBuilder(appId).addTaskCreated(Timestamp(1)).getInstance()
      val overdueStarting = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      val overdueStaging = TestInstanceBuilder.newBuilder(appId).addTaskStaging(Timestamp(1)).getInstance()
      val overdueTaskRunning = TestInstanceBuilder.newBuilder(appId).addTaskRunning(stagedAt = Timestamp(1), startedAt=Timestamp(2)).getInstance()
      val nonOverdueCreating = TestInstanceBuilder.newBuilder(appId).addTaskCreated(Timestamp(now)).getInstance()
      val nonOverdueStarting = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(now)).getInstance()
      val nonOverdueStaging = TestInstanceBuilder.newBuilder(appId).addTaskStaging(Timestamp(now)).getInstance()
      val nonOverdueTaskRunning = TestInstanceBuilder.newBuilder(appId).addTaskRunning(stagedAt = Timestamp(now), startedAt=Timestamp(now)).getInstance()

      val app = InstanceTracker.InstancesBySpec.forInstances(
        overdueCreating,
        overdueStarting,
        overdueStaging,
        overdueTaskRunning,
        nonOverdueCreating,
        nonOverdueStarting,
        nonOverdueStaging,
        nonOverdueTaskRunning
      )

      Given("some overdue and non-overdue tasks in multiple states")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and a reconcile tick are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers reconciliation
      Thread.sleep(100)

      Then("only the overdue tasks in Created, Starting and Staged states should be reconciled")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses =
        (overdueCreating.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus) ++
         overdueStarting.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus) ++
         overdueStaging.instance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus)).toList
      val captor = ArgumentCaptor.forClass(classOf[util.Collection[Protos.TaskStatus]])
      verify(driver, Mockito.timeout(1000).times(1)).reconcileTasks(captor.capture())
      captor.getValue should contain theSameElementsAs tasksStatuses
      noMoreInteractions(killService)
      verifyClean()
    }

    "should not expunge overdue tasks if mesos replies with a `Staging` response" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueInstance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(overdueInstance)

      Given("an overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and two reconciliation ticks are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers 2 reconciliations
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      And("a mesos `Staging` reconciliation response is received")
      sendReconciliationStatusUpdate( // Sends a `Staging` reconciliation response
        ReconciliationStatusUpdate(overdueInstance.tasksMap.valuesIterator.next().taskId, Staging)
      )
      Thread.sleep(100)

      // Now the reconciliation timer should be re-set, so triggering
      // another 2 reconciliations should not cause the task to expunge
      And("two more reconciliations ticks are sent")
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      Then("the task should be reconciled 4, but not expunged")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = overdueInstance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(4)).reconcileTasks(tasksStatuses.asJava)
      noMoreInteractions(killService)
      verifyClean()
    }

    "should expunge overdue tasks if mesos replies with a `Starting` response" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueInstance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(overdueInstance)

      Given("an overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and two reconciliation ticks are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers 2 reconciliations
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      And("a mesos `Starting` reconciliation response is received")
      sendReconciliationStatusUpdate( // Sends a `Staging` reconciliation response
        ReconciliationStatusUpdate(overdueInstance.tasksMap.valuesIterator.next().taskId, Starting)
      )
      Thread.sleep(100)

      // Nothing should be reset, so the next reconciliation will be the last and
      // the next will expunge the task
      And("two more reconciliations ticks are sent")
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      Then("the task should be reconciled 4, but not expunged")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = overdueInstance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(3)).reconcileTasks(tasksStatuses.asJava)
      verify(killService).killInstance(overdueInstance, KillReason.Overdue)
      verifyClean()
    }

    "should not track overdue tasks if mesos replies with a `Running` response" in new Fixture {
      val now: Instant = Instant.now(clock)
      val appId = PathId("/some")
      val overdueInstance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(Timestamp(1)).getInstance()
      val app = InstanceTracker.InstancesBySpec.forInstances(overdueInstance)

      Given("an overdue task")
      instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(app)

      When("a check tick and two reconciliation ticks are sent")
      sendCheckTick(Instant.now(clock)) // Triggers pulling of instances
      Thread.sleep(100)
      sendReconcileTick(Instant.now(clock)) // Triggers 2 reconciliations
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      And("a mesos `Running` reconciliation response is received")
      sendReconciliationStatusUpdate( // Sends a `Staging` reconciliation response
        ReconciliationStatusUpdate(overdueInstance.tasksMap.valuesIterator.next().taskId, Running)
      )
      Thread.sleep(100)

      // The instance should be removed from the tracker. Triggering reconciliations
      // should not do anything right now.
      And("two more reconciliations ticks are sent")
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      sendReconcileTick(Instant.now(clock))
      Thread.sleep(100)

      Then("the task should be reconciled 4, but not expunged")
      verify(instanceTracker, Mockito.timeout(1000)).instancesBySpec()(any[ExecutionContext])
      val tasksStatuses = overdueInstance.tasksMap.valuesIterator.flatMap(_.status.mesosStatus).toList
      verify(driver, Mockito.timeout(1000).times(2)).reconcileTasks(tasksStatuses.asJava)
      noMoreInteractions(killService)
      verifyClean()
    }

  }

  class ReconciliationTrackerFixture(maxReconciliations: Int) {
    val (instancesProbe, _instances: Source[Instance, NotUsed]) = TestSource.probe[Instance].preMaterialize()
    val (statusUpdatesProbe, _statusUpdates) = TestSource.probe[ReconciliationStatusUpdate].preMaterialize()
    val (reconciliationTickProbe, _reconciliationTick) = TestSource.probe[Instant].preMaterialize()

    val (taskKillerProbe, _taskKiller) = TestSink.probe[Instance].preMaterialize()

    val reconciliationProbe = TestProbe()

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
        new ReconciliationTracker(tasks => { reconciliationProbe.ref ! tasks; Future.successful(Done) }, 2, maxReconciliations)
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
      reconciliationProbe.expectMsg(instance.tasksMap.values.toSeq)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNoMessage(100.millis)
      reconciliationProbe.expectMsg(instance.tasksMap.values.toSeq)

      reconciliationTickProbe.sendNext(Instant.now())
      taskKillerProbe.expectNext(instance)
      reconciliationProbe.expectNoMessage(100.millis)

    }

    "stop tracking instances once a proper status update arrives" in new ReconciliationTrackerFixture(1) {
      tracker.run()

      val instance = newInstance()

      taskKillerProbe.request(1)

      instancesProbe.sendNext(instance)

      statusUpdatesProbe.sendNext(ReconciliationStatusUpdate(taskId = instance.tasksMap.head._1, Condition.Running))

      reconciliationTickProbe.sendNext(Instant.now())
      reconciliationProbe.expectNoMessage(100.millis)
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      reconciliationProbe.expectNoMessage(100.millis)
      taskKillerProbe.expectNoMessage(100.millis)

    }

    "reset reconciliation attempt count if staging status is confirmed" in new ReconciliationTrackerFixture(1) {
      tracker.run()

      val instance = newInstance()

      taskKillerProbe.request(1)

      instancesProbe.sendNext(instance)

      reconciliationTickProbe.sendNext(Instant.now())
      reconciliationProbe.expectMsg(instance.tasksMap.values.toSeq)
      taskKillerProbe.expectNoMessage(100.millis)

      statusUpdatesProbe.sendNext(ReconciliationStatusUpdate(taskId = instance.tasksMap.head._1, Condition.Staging))
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      reconciliationProbe.expectMsg(instance.tasksMap.values.toSeq)
      taskKillerProbe.expectNoMessage(100.millis)

      reconciliationTickProbe.sendNext(Instant.now())
      reconciliationProbe.expectNoMessage(100.millis)
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
