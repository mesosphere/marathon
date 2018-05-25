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
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{AppDefinition, Command}
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class TaskStartActorTest extends AkkaUnitTest with Eventually {
  "TaskStartActor" should {

    "Start success no items in the queue" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 5)

      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)
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

      val instances: Seq[Instance] = Seq(Instance.Scheduled(app))
      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(instances)

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
      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(instances)

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

      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

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
      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

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
      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }

    "Task fails to start" in {
      val f = new Fixture
      val promise = Promise[Unit]()
      val app = AppDefinition("/myApp".toPath, instances = 1)

      f.taskTracker.specInstances(eq(app.id))(any) returns Future.successful(Seq.empty)

      val ref = f.startActor(app, app.instances, promise)
      watch(ref)

      eventually { verify(f.launchQueue, atLeastOnce).add(app, app.instances) }

      system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Failed))

      eventually { verify(f.launchQueue, atLeastOnce).add(app, 1) }

      for (i <- 0 until app.instances)
        system.eventStream.publish(f.instanceChange(app, Instance.Id.forRunSpec(app.id), Running))

      promise.future.futureValue should be(())

      expectTerminated(ref)
    }
  }

      // and make sure that the actor should finishes
      promise.future.futureValue should be(())

      expectTerminated(ref)
    }
  }

  "relaunch when resident task gets terminal" in {
    val f = new Fixture
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath)

    f.launchQueue.get(app.id) returns Future.successful(None)
    f.taskTracker.countActiveSpecInstances(app.id) returns Future.successful(0)

    val ref = f.startActor(app, app.instances, promise)
    watch(ref)

    eventually { verify(f.launchQueue, times(1)).add(eq(app), any) }

    // let existing task die
    var instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(None).getInstance()
    val reservedTask: Task = instance.appTask
    instance = instance.copy(tasksMap = Map(reservedTask.taskId -> reservedTask.copy(status = reservedTask.status.copy(mesosStatus = Some(MesosTaskStatusTestHelper.failed(reservedTask.taskId))))))
    // we need this because otherwise the timer for Sync could fire up and that is not what we are trying to test
    f.taskTracker.countActiveSpecInstances(app.id) returns Future.successful(1)
    system.eventStream.publish(InstanceChanged(instance.instanceId, app.version, app.id, instance.state.condition, instance))

    eventually { verify(f.launchQueue, times(2)).add(eq(app), any) }
  }

  class Fixture {

    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    val taskTracker: InstanceTracker = mock[InstanceTracker]
    val deploymentManager = TestProbe()
    val status: DeploymentStatus = mock[DeploymentStatus]
    val readinessCheckExecutor: ReadinessCheckExecutor = mock[ReadinessCheckExecutor]

    launchQueue.add(any, any) returns Future.successful(Done)

    def instanceChange(app: AppDefinition, id: Instance.Id, condition: Condition): InstanceChanged = {
      val instance: Instance = mock[Instance]
      instance.instanceId returns id
      InstanceChanged(id, app.version, app.id, condition, instance)
    }

    def healthChange(app: AppDefinition, id: Instance.Id, healthy: Boolean): InstanceHealthChanged = {
      InstanceHealthChanged(id, app.version, app.id, Some(healthy))
    }

    def startActor(app: AppDefinition, scaleTo: Int, promise: Promise[Unit]): TestActorRef[TaskStartActor] =
      TestActorRef(childSupervisor(TaskStartActor.props(
        deploymentManager.ref, status, scheduler, launchQueue, taskTracker, system.eventStream, readinessCheckExecutor,
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
