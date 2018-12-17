package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.{Status, Terminated}
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceUpdateOpResolver, InstanceUpdateOperation}
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.task.TaskCondition
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.UpdateContext
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerUpdateStepProcessor}
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.storage.repository.InstanceView
import mesosphere.marathon.test.SettableClock
import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks.{Table, forAll}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Most of the functionality is tested at a higher level in [[mesosphere.marathon.tasks.InstanceTrackerImplTest]].
  */
class InstanceTrackerActorTest extends AkkaUnitTest with Eventually {
  override lazy val akkaConfig =
    ConfigFactory.parseString(""" akka.actor.guardian-supervisor-strategy = "akka.actor.StoppingSupervisorStrategy" """)
      .withFallback(ConfigFactory.load())

  val metricsModules = Table(
    ("name", "module"),
    ("dropwizard", MetricsModule(AllConf.withTestConfig()))
  )

  forAll (metricsModules) { (name: String, metricsModule: MetricsModule) =>
    s"InstanceTrackerActor (metrics = $name)" should {
      "failures while loading the initial data are escalated" in {
        val f = new Fixture

        Given("a failing task loader")
        f.taskLoader.load() returns Future.failed(new RuntimeException("severe simulated loading failure"))

        When("the task tracker starts")
        f.taskTrackerActor

        Then("it will call the failing load method")
        verify(f.taskLoader).load()

        And("it will eventually die")
        watch(f.taskTrackerActor)
        expectMsgClass(classOf[Terminated]).getActor should be(f.taskTrackerActor)
      }

      "answers with loaded data (empty)" in {
        val f = new Fixture
        Given("an empty task loader result")
        val appDataMap = InstanceTracker.InstancesBySpec.empty
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("the task tracker actor gets a List query")
        val probe = TestProbe()
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)

        Then("it will eventually answer")
        probe.expectMsg(appDataMap)
      }

      "answers with loaded data (some data)" in {
        val f = new Fixture
        Given("a task loader with one running instance")
        val appId: PathId = PathId("/app")
        val instance = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(instance)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("the task tracker actor gets a List query")
        val probe = TestProbe()
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)

        Then("it will eventually answer")
        probe.expectMsg(appDataMap)
      }

      "correctly calculates metrics for loaded data" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("the task tracker has started up")
        val probe = TestProbe()
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
        probe.expectMsg(appDataMap)

        Then("it will have set the correct metric counts")
        f.actorMetrics.runningTasksMetric.value should be(2)
        f.actorMetrics.stagedTasksMetric.value should be(1)
      }

      "correctly updates metrics for staged task gets deleted" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("staged task gets deleted")
        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.killed(staged)
        val update = helper.operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]

        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))
        probe.expectMsg(helper.effect)

        Then("it will have set the correct metric counts")
        f.actorMetrics.runningTasksMetric.value should be(2)
        f.actorMetrics.stagedTasksMetric.value should be(0)
      }

      "correctly updates metrics for running task gets deleted" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("running task gets deleted")
        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.killed(runningOne)
        val update = helper.operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]

        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))
        probe.expectMsg(helper.effect)

        Then("it will have set the correct metric counts")
        f.actorMetrics.runningTasksMetric.value should be(1)
        f.actorMetrics.stagedTasksMetric.value should be(1)

        And("update steps have been processed 2 times")
        verify(f.stepProcessor, times(1)).process(any)(any[ExecutionContext])
      }

      "correctly updates metrics for updated tasks" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("staged task transitions to running")
        val probe = TestProbe()
        val stagedInstanceNowRunning = TestInstanceBuilder.newBuilderWithInstanceId(staged.instanceId).addTaskRunning().getInstance()
        val (_, stagedTaskNowRunning) = stagedInstanceNowRunning.tasksMap.head
        val mesosStatus = stagedTaskNowRunning.status.mesosStatus.get
        val helper = TaskStatusUpdateTestHelper.taskUpdateFor(staged, TaskCondition(mesosStatus), mesosStatus)
        val update = helper.operation

        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))
        probe.expectMsg(helper.effect)

        Then("it will have set the correct metric counts")
        f.actorMetrics.runningTasksMetric.value should be(3)
        f.actorMetrics.stagedTasksMetric.value should be(0)
        And("update steps are processed")
        verify(f.stepProcessor).process(any)(any[ExecutionContext])
      }

      "correctly updates metrics for created tasks" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val appDef = AppDefinition(id = appId)
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val scheduled = Instance.scheduled(appDef)
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo, scheduled)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("a new staged task gets added")
        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.provision(scheduled, f.clock.now())
        val update = helper.operation

        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))
        probe.expectMsg(helper.effect)

        Then("it will have set the correct metric counts")
        eventually {
          f.actorMetrics.runningTasksMetric.value should be(2)
          f.actorMetrics.stagedTasksMetric.value should be(2)
        }
        And("update steps are processed")
        verify(f.stepProcessor).process(any)(any[ExecutionContext])
      }

      "updates repository as well as internal state for instance update" in {
        Given("an task loader with one staged and two running instances")
        val f = new Fixture
        val appId: PathId = PathId("/app")
        val appDef = AppDefinition(id = appId)
        val staged = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val scheduled = Instance.scheduled(appDef)
        val runningOne = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningTwo = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo, scheduled)
        f.taskLoader.load() returns Future.successful(appDataMap)

        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.provision(scheduled, f.clock.now())
        val update = UpdateContext(f.clock.now() + 3.days, helper.operation)

        When("Instance update is received")
        probe.send(f.taskTrackerActor, update)
        probe.expectMsg(helper.effect)

        Then("instance repository save is called")
        verify(f.repository).store(helper.wrapped.instance)

        And("internal state is updated")
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
        probe.expectMsg(InstanceTracker.InstancesBySpec.forInstances(staged, runningOne, runningTwo, helper.wrapped.instance))
      }

      "fails when repository call fails for update" in {
        val f = new Fixture
        Given("an task loader with one staged and two running instances")
        val appId: PathId = PathId("/app")
        val scheduled = Instance.scheduled(AppDefinition(appId))
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(scheduled)
        f.taskLoader.load() returns Future.successful(appDataMap)

        And("repository that returns error for store operation")
        f.repository.store(any) returns Future.failed(new RuntimeException("fail"))

        When("an update to provisioned is sent")
        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.provision(scheduled, f.clock.now())
        val update = UpdateContext(f.clock.now() + 3.days, helper.operation)

        probe.send(f.taskTrackerActor, update)

        Then("Failure message is received")
        probe.fishForSpecificMessage() {
          case _: Status.Failure => true
          case _ => false
        }

        And("Internal state did not change")
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
        probe.expectMsg(appDataMap)
      }

      "updates repository as well as internal state for instance expunge" in {
        Given("a task loader with update operation received")
        val f = new Fixture
        val appId: PathId = PathId("/app")
        val running = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningDecommissioned = running.copy(state = running.state.copy(goal = Goal.Decommissioned))
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(runningDecommissioned)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("a running and decommissioned task is killed")
        val probe = TestProbe()
        val helper = TaskStatusUpdateTestHelper.killed(runningDecommissioned)
        val update = helper.operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]

        And("and expunged")
        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))
        probe.expectMsg(helper.effect)

        Then("repository is updated")
        verify(f.repository).delete(helper.wrapped.id)

        And("internal state is updated")
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
        probe.expectMsg(InstanceTracker.InstancesBySpec.empty)
      }

      "fails after failure during repository call to expunge" in {
        val f = new Fixture
        Given("an task instance tracker with initial state")
        val appId: PathId = PathId("/app")
        val running = TestInstanceBuilder.newBuilder(appId).addTaskRunning().getInstance()
        val runningDecommissioned = running.copy(state = running.state.copy(goal = Goal.Decommissioned))
        val appDataMap = InstanceTracker.InstancesBySpec.forInstances(runningDecommissioned)
        f.taskLoader.load() returns Future.successful(appDataMap)

        When("a task in decommissioned gets killed")
        val probe = TestProbe()
        val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
        val helper = TaskStatusUpdateTestHelper.killed(instance)
        val update = helper.operation.asInstanceOf[InstanceUpdateOperation.MesosUpdate]

        And("repository store operation fails")
        f.repository.delete(instance.instanceId) returns Future.failed(new RuntimeException("fail"))

        probe.send(f.taskTrackerActor, UpdateContext(f.clock.now() + 3.days, update))

        Then("failure message is sent")
        probe.fishForSpecificMessage() {
          case _: Status.Failure => true
          case _ => false
        }

        And("internal state did not change")
        probe.send(f.taskTrackerActor, InstanceTrackerActor.List)
        probe.expectMsg(appDataMap)
      }
    }

    class Fixture {
      val clock = SettableClock.ofNow()

      val updateResolver = new InstanceUpdateOpResolver(clock)
      lazy val taskLoader = mock[InstancesLoader]
      lazy val stepProcessor = mock[InstanceTrackerUpdateStepProcessor]
      lazy val metrics = metricsModule.metrics
      lazy val actorMetrics = new InstanceTrackerActor.ActorMetrics(metrics)
      lazy val repository = mock[InstanceView]
      repository.store(any) returns Future.successful(Done)
      repository.delete(any) returns Future.successful(Done)

      stepProcessor.process(any)(any[ExecutionContext]) returns Future.successful(Done)

      lazy val taskTrackerActor = TestActorRef[InstanceTrackerActor](InstanceTrackerActor.props(actorMetrics, taskLoader, stepProcessor, updateResolver, repository, clock))

      def verifyNoMoreInteractions(): Unit = {
        noMoreInteractions(taskLoader)
        reset(taskLoader)
      }
    }
  }
}
