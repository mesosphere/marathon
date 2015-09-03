package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, ActorSystem, PoisonPill, Terminated }
import akka.testkit.TestProbe
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec }
import mesosphere.mesos.protos.TaskID
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.Matchers.any

class KillOverdueTasksActorTest extends MarathonSpec {
  implicit var actorSystem: ActorSystem = _
  var taskTracker: TaskTracker = _
  var driver: SchedulerDriver = _
  var checkActor: ActorRef = _
  val clock = ConstantClock()

  before {
    actorSystem = ActorSystem()
    taskTracker = mock[TaskTracker]
    driver = mock[SchedulerDriver]
    val driverHolder = new MarathonSchedulerDriverHolder()
    driverHolder.driver = Some(driver)
    checkActor = actorSystem.actorOf(KillOverdueTasksActor.props(taskTracker, driverHolder, clock), "check")
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

  test("no overdue tasks") {
    when(taskTracker.determineOverdueTasks(any())).thenReturn(Seq.empty)

    checkActor ! KillOverdueTasksActor.Check

    verify(taskTracker, Mockito.timeout(1000)).determineOverdueTasks(any())
    // no interactions expected
  }

  test("some overdue tasks") {
    val mockTask = MarathonTask.newBuilder().setId("someId").buildPartial()
    when(taskTracker.determineOverdueTasks(any())).thenReturn(Seq(mockTask))

    checkActor ! KillOverdueTasksActor.Check

    verify(taskTracker, Mockito.timeout(1000)).determineOverdueTasks(any())
    import mesosphere.mesos.protos.Implicits._
    verify(driver, Mockito.timeout(1000)).killTask(TaskID("someId"))
  }
}
