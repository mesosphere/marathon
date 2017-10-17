package mesosphere.marathon
package core.task.termination.impl

import java.util.UUID

import akka.Done
import akka.actor.{ ActorRef, PoisonPill, Terminated }
import akka.testkit.TestProbe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ InstanceChanged, UnknownInstanceTerminated }
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceUpdateOperation }
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.termination.KillConfig
import mesosphere.marathon.core.task.tracker.TaskStateOpProcessor
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor

import scala.concurrent.Promise
import scala.concurrent.duration._

class KillServiceActorTest extends AkkaUnitTest with StrictLogging {

  val defaultConfig: KillConfig = new KillConfig {
    override lazy val killChunkSize: Int = 5
    override lazy val killRetryTimeout: FiniteDuration = 10.minutes
  }
  val retryConfig: KillConfig = new KillConfig {
    override lazy val killChunkSize: Int = 5
    override lazy val killRetryTimeout: FiniteDuration = 500.millis
  }

  "The KillServiceActor" when {

    "asked to kill a single known instance" should {
      "issue a kill to the driver" in withActor(defaultConfig) { (f, actor) =>
        val instance = f.mockInstance(f.runSpecId, f.now(), mesos.Protos.TaskState.TASK_RUNNING)

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        val (taskId, _) = instance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)

        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance).wrapped)

        promise.future.futureValue should be(Done)
      }
    }

    "asked to kill an unknown instance" should {
      "issue a kill to the driver" in withActor(defaultConfig) { (f, actor) =>

        val taskId = Task.Id.forRunSpec(PathId("/unknown"))
        actor ! KillServiceActor.KillUnknownTaskById(taskId)
        f.publishUnknownInstanceTerminated(taskId.instanceId)

        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)
        noMoreInteractions(f.driver)
      }
    }

    "asked to kill single known unreachable instance" should {
      "issue no kill to the driver because the task is unreachable and send an expunge" in withActor(defaultConfig) { (f, actor) =>

        val instance = f.mockInstance(f.runSpecId, f.now(), mesos.Protos.TaskState.TASK_UNREACHABLE)
        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        noMoreInteractions(f.driver)
        verify(f.stateOpProcessor, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).process(InstanceUpdateOperation.ForceExpunge(instance.instanceId))

        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance).wrapped)

        promise.future.futureValue should be(Done)
      }
    }

    "asked to kill multiple instances at once" should {
      "issue three kill requests to the driver" in withActor(defaultConfig) { (f, actor) =>
        val runningInstance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
        val unreachableInstance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_UNREACHABLE)
        val stagingInstance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_STAGING)

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(runningInstance, unreachableInstance, stagingInstance), promise)

        val (runningTaskId, _) = runningInstance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(runningTaskId.mesosTaskId)
        verify(f.stateOpProcessor, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).process(InstanceUpdateOperation.ForceExpunge(unreachableInstance.instanceId))

        val (stagingTaskId, _) = stagingInstance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(stagingTaskId.mesosTaskId)
        noMoreInteractions(f.driver)

        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(runningInstance).wrapped)
        f.publishInstanceChanged(TaskStatusUpdateTestHelper.gone(unreachableInstance).wrapped)
        f.publishInstanceChanged(TaskStatusUpdateTestHelper.unreachable(stagingInstance).wrapped)

        promise.future.futureValue should be (Done)
      }
    }

    "asked to kill multiple tasks at once with an empty list" should {
      "issue no kill" in withActor(defaultConfig) { (f, actor) =>

        val emptyList = Seq.empty[Instance]
        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(emptyList, promise)

        promise.future.futureValue should be (Done)
        noMoreInteractions(f.driver)
      }
    }

    "asked to kill multiple instances subsequently" should {
      "issue exactly 3 kills to the driver and complete the future successfully" in withActor(defaultConfig) { (f, actor) =>
        val instance1 = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
        val instance2 = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
        val instance3 = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)

        val promise1 = Promise[Done]()
        val promise2 = Promise[Done]()
        val promise3 = Promise[Done]()

        actor ! KillServiceActor.KillInstances(Seq(instance1), promise1)
        actor ! KillServiceActor.KillInstances(Seq(instance2), promise2)
        actor ! KillServiceActor.KillInstances(Seq(instance3), promise3)

        val (taskId1, _) = instance1.tasksMap.head
        val (taskId2, _) = instance2.tasksMap.head
        val (taskId3, _) = instance3.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId1.mesosTaskId)
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId2.mesosTaskId)
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId3.mesosTaskId)
        noMoreInteractions(f.driver)

        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance1).wrapped)
        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance2).wrapped)
        f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance3).wrapped)

        promise1.future.futureValue should be (Done)
        promise2.future.futureValue should be (Done)
        promise3.future.futureValue should be (Done)
      }
    }

    "killing instances is throttled (single requests)" should {
      "issue 5 kills immediately to the driver" in withActor(defaultConfig) { (f, actor) =>
        val instances: Map[Instance.Id, Instance] = (1 to 10).map { index =>
          val instance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
          instance.instanceId -> instance
        }(collection.breakOut)

        instances.valuesIterator.foreach { instance =>
          actor ! KillServiceActor.KillInstances(Seq(instance), Promise[Done]())
        }

        val captor: ArgumentCaptor[mesos.Protos.TaskID] = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(5)).killTask(captor.capture())
        reset(f.driver)

        captor.getAllValues.foreach { id =>
          val instanceId = Task.Id(id).instanceId
          instances.get(instanceId).foreach { instance =>
            f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance).wrapped)
          }
        }

        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(5)).killTask(any)
        noMoreInteractions(f.driver)
      }
    }

    "killing instances is throttled (batch request)" should {
      "issue 5 kills immediately to the driver" in withActor(defaultConfig) { (f, actor) =>

        val instances: Map[Instance.Id, Instance] = (1 to 10).map { index =>
          val instance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
          instance.instanceId -> instance
        }(collection.breakOut)

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(instances.values.to[Seq], promise)

        val captor: ArgumentCaptor[mesos.Protos.TaskID] = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(5)).killTask(captor.capture())
        reset(f.driver)

        captor.getAllValues.foreach { id =>
          val instanceId = Task.Id(id).instanceId
          instances.get(instanceId).foreach { instance =>
            f.publishInstanceChanged(TaskStatusUpdateTestHelper.killed(instance).wrapped)
          }
        }

        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(5)).killTask(any)
        noMoreInteractions(f.driver)
      }
    }

    "killing with retry will be retried" should {
      "issue a kill to the driver an eventually retry" in withActor(retryConfig) { (f, actor) =>
        val instance = f.mockInstance(f.runSpecId, f.clock.now(), mesos.Protos.TaskState.TASK_RUNNING)
        val promise = Promise[Done]()

        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        val (taskId, _) = instance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)

        f.clock.+=(10.seconds)

        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)
      }
    }

    "when asked to kill all non-terminal tasks of a pod instance" should {
      "issue 2 kills to the driver and retry eventually" in withActor(defaultConfig) { (f, actor) =>
        val stagingContainer = f.container("stagingContainer")
        val runningContainer = f.container("runningContainer")
        val finishedContainer = f.container("finishedContainer")
        var instance = TestInstanceBuilder.newBuilder(f.runSpecId)
          .addTaskStaged(containerName = Some(stagingContainer.name))
          .addTaskStaged(containerName = Some(runningContainer.name))
          .addTaskStaged(containerName = Some(finishedContainer.name))
          .getInstance()
        instance = TaskStatusUpdateTestHelper.running(instance, Some(runningContainer)).updatedInstance
        instance = TaskStatusUpdateTestHelper.finished(instance, Some(finishedContainer)).updatedInstance

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        val captor = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(2)).killTask(captor.capture())

        captor.getAllValues should have size 2
        captor.getAllValues should contain(f.taskIdFor(instance, stagingContainer))
        captor.getAllValues should contain(f.taskIdFor(instance, runningContainer))

        f.clock.+=(10.seconds)

        val (taskId, _) = instance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)
      }
    }

    "when killing all non-terminal tasks of a pod instance" should {
      "issue 2 kills to the driver" in withActor(defaultConfig) { (f, actor) =>
        val stagingContainer = f.container("stagingContainer")
        val runningContainer = f.container("runningContainer")
        val finishedContainer = f.container("finishedContainer")
        var instance = TestInstanceBuilder.newBuilder(f.runSpecId)
          .addTaskStaged(containerName = Some(stagingContainer.name))
          .addTaskStaged(containerName = Some(runningContainer.name))
          .addTaskStaged(containerName = Some(finishedContainer.name))
          .getInstance()
        instance = TaskStatusUpdateTestHelper.running(instance, Some(runningContainer)).updatedInstance
        instance = TaskStatusUpdateTestHelper.finished(instance, Some(finishedContainer)).updatedInstance

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        val captor = ArgumentCaptor.forClass(classOf[mesos.Protos.TaskID])
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2).times(2)).killTask(captor.capture())

        captor.getAllValues should have size 2
        captor.getAllValues should contain(f.taskIdFor(instance, stagingContainer))
        captor.getAllValues should contain(f.taskIdFor(instance, runningContainer))

        f.clock.+=(10.seconds)

        val (taskId, _) = instance.tasksMap.head
        verify(f.driver, timeout(f.killConfig.killRetryTimeout.toMillis.toInt * 2)).killTask(taskId.mesosTaskId)
      }
    }

    "a pod instance with only terminal tasks will be expunged and no kills are issued" should {
      "issue no kills" in withActor(defaultConfig) { (f, actor) =>
        val finishedContainer1 = f.container("finishedContainer1")
        val finishedContainer2 = f.container("finishedContainer2")
        var instance = TestInstanceBuilder.newBuilder(f.runSpecId)
          .addTaskRunning(containerName = Some(finishedContainer1.name))
          .addTaskRunning(containerName = Some(finishedContainer2.name))
          .getInstance()
        instance = TaskStatusUpdateTestHelper.finished(instance, Some(finishedContainer1)).updatedInstance
        instance = TaskStatusUpdateTestHelper.finished(instance, Some(finishedContainer2)).updatedInstance

        val promise = Promise[Done]()
        actor ! KillServiceActor.KillInstances(Seq(instance), promise)

        noMoreInteractions(f.driver)

        verify(f.stateOpProcessor, timeout(f.killConfig.killRetryTimeout.toMillis.toInt)).process(InstanceUpdateOperation.ForceExpunge(instance.instanceId))
      }
    }
  }

  def withActor(killConfig: KillConfig)(testCode: (Fixture, ActorRef) => Any): Unit = {
    val f = new Fixture(killConfig)
    val actor = system.actorOf(KillServiceActor.props(f.driverHolder, f.stateOpProcessor, killConfig, f.clock), s"KillService-${UUID.randomUUID()}")

    try {
      testCode(f, actor)
    } finally {
      actor ! PoisonPill
      val probe = TestProbe()
      probe.watch(actor)
      val terminated = probe.expectMsgAnyClassOf(classOf[Terminated])
      assert(terminated.actor == actor)
    }
  }

  class Fixture(val killConfig: KillConfig) {

    val runSpecId = PathId("/test")
    val driver = mock[SchedulerDriver]
    val driverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(driver)
      holder
    }
    val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    val clock = new SettableClock()

    def mockInstance(appId: PathId, stagedAt: Timestamp, mesosState: mesos.Protos.TaskState): Instance = {
      TestInstanceBuilder.newBuilder(appId).addTaskWithBuilder().taskForStatus(mesosState, stagedAt).build().getInstance()
    }

    def publishInstanceChanged(instanceChange: InstanceChange): Unit = {
      val instanceChangedEvent = InstanceChanged(instanceChange)
      logger.info("publish {} on the event stream", instanceChangedEvent)
      system.eventStream.publish(instanceChangedEvent)
    }

    def publishUnknownInstanceTerminated(instanceId: Instance.Id): Unit = {
      val event = UnknownInstanceTerminated(instanceId, instanceId.runSpecId, Condition.Killed)
      logger.info("publish {} on the event stream", event)
      system.eventStream.publish(event)
    }

    def now(): Timestamp = Timestamp.zero

    def container(name: String) = MesosContainer(name = name, resources = Resources())

    def taskIdFor(instance: Instance, container: MesosContainer): mesos.Protos.TaskID = {
      val taskId = Task.Id.forInstanceId(instance.instanceId, Some(container))
      taskId.mesosTaskId
    }

  }
}
