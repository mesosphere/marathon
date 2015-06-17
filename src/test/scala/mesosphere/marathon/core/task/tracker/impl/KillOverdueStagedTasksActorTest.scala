package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Terminated }
import akka.testkit.TestProbe
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import mesosphere.mesos.protos.TaskID
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito._

class KillOverdueStagedTasksActorTest extends MarathonSpec {
  implicit var actorSystem: ActorSystem = _
  var taskTracker: TaskTracker = _
  var driver: SchedulerDriver = _
  var checkActor: ActorRef = _

  before {
    actorSystem = ActorSystem()
    taskTracker = mock[TaskTracker]
    driver = mock[SchedulerDriver]
    val driverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)
    checkActor = actorSystem.actorOf(KillOverdueStagedTasksActor.props(taskTracker, driverHolder), "check")
  }

  after {
    def waitForActorProcessingAllAndDying(): Unit = {
      checkActor ! PoisonPill
      val probe = TestProbe()
      probe.watch(checkActor)
      val terminated = probe.expectMsgAnyClassOf(classOf[Terminated])
      assert(terminated.actor == checkActor)
    }

    waitForActorProcessingAllAndDying()

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    verifyNoMoreInteractions(taskTracker, driver)
  }

  test("no overdue staged tasks") {
    when(taskTracker.checkStagedTasks).thenReturn(Seq.empty)

    checkActor ! KillOverdueStagedTasksActor.Check

    verify(taskTracker, Mockito.timeout(1000)).checkStagedTasks
    // no interactions expected
  }

  test("some overdue staged tasks") {
    val mockTask = MarathonTask.newBuilder().setId("someId").buildPartial()
    when(taskTracker.checkStagedTasks).thenReturn(Seq(mockTask))

    checkActor ! KillOverdueStagedTasksActor.Check

    verify(taskTracker, Mockito.timeout(1000)).checkStagedTasks
    import mesosphere.mesos.protos.Implicits._
    verify(driver, Mockito.timeout(1000)).killTask(TaskID("someId"))
  }
}
