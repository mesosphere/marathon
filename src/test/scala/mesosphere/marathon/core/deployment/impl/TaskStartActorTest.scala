package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.{OneForOneStrategy, Props, SupervisorStrategy}
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.{Failed, Running}
import mesosphere.marathon.core.event.{DeploymentStatus, _}
import mesosphere.marathon.core.health.MesosCommandHealthCheck
import mesosphere.marathon.core.instance.Goal.{Decommissioned, Stopped}
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{AppDefinition, Command, Timestamp}
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class TaskStartActorTest extends AkkaUnitTest with Eventually {
  "TaskStartActor" should {

    "Start success no items in the queue" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)
      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Start success with one task left to launch" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      val instances: Seq[Instance] = Seq(Instance.scheduled(app))
      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(instances)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances - 1) }

      for (i <- 0 until (app.instances - 1))
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Start success with existing task in launch queue" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      val instances: Seq[Instance] = Seq(TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance())
      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(instances)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances - 1) }

      for (i <- 0 until (app.instances - 1))
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Start success with no instances to start" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 0)

      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Start with health checks" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition(
        "/myApp".toPath,
        instances = 5,
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true")))
      )
      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.healthChange(app, Instance.Id.forRunSpec(app.id), healthy = true))

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Start with health checks with no instances to start" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition(
        "/myApp".toPath,
        instances = 0,
        healthChecks = Set(MesosCommandHealthCheck(command = Command("true")))
      )
      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "task fails to start but the goal of instance is still running" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 2)

      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Failed))

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      promise.future.futureValue should be(())

      // this is just making sure that when task previously fail, we don't try to scale it by calling launchqueue
      // that is now handled within the scheduler itself so if the goal is running, orchestrator should not do anything
      verify(f.launchQueue, never).add(app, 1)

      expectTerminated(ref)
    }

    "task fails and the instance is decommissioned while starting an app" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 2)

      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Failed, Decommissioned))

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      // for decommissioned instances, we need to schedule a new one
      eventually { verify(f.launchQueue, once).add(app, 1) }

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "task fails and the instance is stopped while starting an app" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 2)

      f.instanceTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Failed, Stopped))

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      // for stopped instances, we need to schedule a new one
      eventually { verify(f.launchQueue, once).add(app, 1) }

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }
  }

  class Fixture {

    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val instanceTracker: InstanceTracker = mock[InstanceTracker]
    val deploymentManager = TestProbe()
    val status: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    launchQueue.add(any, any) returns Future.successful(Done)

    def instanceChange(app: AppDefinition, id: Instance.Id, condition: Condition, goal: Goal = Goal.Running): InstanceChanged = {
      val instance: Instance = mock[Instance]
      instance.instanceId returns id
      instance.state returns Instance.InstanceState(condition, Timestamp.now(), None, None, goal)
      InstanceChanged(id, app.version, app.id, condition, instance)
    }

    def healthChange(app: AppDefinition, id: Instance.Id, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(id, app.version, app.id, Some(healthy))
    }

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[TaskStartActor] =
      TestActorRef(childSupervisor(TaskStartActor.props(
        deploymentManager.ref, status, launchQueue, instanceTracker, system.eventStream, readinessCheckExecutor,
        app, scaleTo, promise), "Test-TaskStartActor"))

    // Prevents the TaskActor from restarting too many times (filling the log with exceptions) similar to how it's
    // parent actor (DeploymentActor) does it.
    def childSupervisor(props: Props, name: String): Props = {
      import scala.concurrent.duration._

      BackoffSupervisor.props(
        Backoff.onFailure(
          childProps = props,
          childName = name,
          minBackoff = 5.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(
          OneForOneStrategy() {
            case NonFatal(_) => SupervisorStrategy.Restart
            case _ => SupervisorStrategy.Escalate
          }
        ))
    }
  }
}
