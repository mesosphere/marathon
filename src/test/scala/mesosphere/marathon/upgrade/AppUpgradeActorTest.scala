package mesosphere.marathon.upgrade

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.{ActorPath, Props, ActorSystem}
import mesosphere.marathon.MarathonSpec
import org.scalatest.{Matchers, BeforeAndAfterAll}
import org.scalatest.mock.MockitoSugar
import mesosphere.marathon.state.AppRepository
import mesosphere.marathon.tasks.{TaskQueue, TaskTracker}
import org.apache.mesos.SchedulerDriver
import akka.util.Timeout
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.duration._
import mesosphere.marathon.event.{HealthStatusChanged, MesosStatusUpdateEvent}
import org.mockito.Mockito.{when, verify, times}
import scala.collection.mutable
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.upgrade.AppUpgradeManager.UpgradeFinished
import org.apache.mesos.Protos.TaskID
import akka.pattern.ask
import scala.concurrent.Await

class AppUpgradeActorTest
  extends TestKit(ActorSystem("System"))
  with MarathonSpec
  with Matchers
  with BeforeAndAfterAll
  with MockitoSugar {

  var repo: AppRepository = _
  var tracker: TaskTracker = _
  var queue: TaskQueue = _
  var driver: SchedulerDriver = _

  implicit val defaultTimeout: Timeout = 5.seconds

  before {
    driver = mock[SchedulerDriver]
    repo = mock[AppRepository]
    tracker = mock[TaskTracker]
    queue = mock[TaskQueue]
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  test("Upgrade") {
    val app = AppDefinition(id = "MyApp", instances = 2)
    val taskA = MarathonTask.newBuilder().setId("task_a").build()
    val taskB = MarathonTask.newBuilder().setId("task_b").build()

    when(tracker.get(app.id)).thenReturn(mutable.Set(taskA, taskB))

    val ref = TestActorRef(Props(new AppUpgradeActor(
      testActor,
      driver,
      tracker,
      queue,
      system.eventStream,
      app,
      1,
      testActor
    )))

    // make sure all the children are running
    awaitStartup(ref.path / "Replacer")
    awaitStartup(ref.path / "Stopper")
    awaitStartup(ref.path / "Starter")

    system.eventStream.publish(MesosStatusUpdateEvent("", "task_a", "TASK_KILLED", "MyApp", "", Nil, ""))

    system.eventStream.publish(MesosStatusUpdateEvent("", "task_c", "TASK_RUNNING", "MyApp", "", Nil, app.version.toString))
    system.eventStream.publish(MesosStatusUpdateEvent("", "task_d", "TASK_RUNNING", "MyApp", "", Nil, app.version.toString))

    system.eventStream.publish(HealthStatusChanged("MyApp", "task_c", true))
    system.eventStream.publish(HealthStatusChanged("MyApp", "task_d", true))

    system.eventStream.publish(MesosStatusUpdateEvent("", "task_b", "TASK_KILLED", "MyApp", "", Nil, ""))

    expectMsg(5.seconds, UpgradeFinished(app.id))

    verify(tracker).get(app.id)
    verify(driver).killTask(TaskID.newBuilder().setValue("task_a").build())
    verify(driver).killTask(TaskID.newBuilder().setValue("task_b").build())

    verify(queue, times(2)).add(app)
  }

  def awaitStartup(path: ActorPath): Unit = {
    val selection = system.actorSelection(path)

    Await.ready(selection.resolveOne(), 5.seconds)
  }
}
