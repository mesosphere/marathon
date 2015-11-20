package mesosphere.marathon.event

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ TaskFailure, TaskFailureRepository, Timestamp }
import org.apache.mesos.Protos.{ NetworkInfo, TaskState }
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

class HistoryActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with MockitoSugar
    with BeforeAndAfterAll
    with Matchers
    with ImplicitSender {
  import org.apache.mesos.Protos.TaskState._

  var historyActor: ActorRef = _
  var failureRepo: TaskFailureRepository = _

  before {
    failureRepo = mock[TaskFailureRepository]
    historyActor = TestActorRef(Props(
      new HistoryActor(system.eventStream, failureRepo)
    ))
  }

  test("Store TASK_FAILED") {
    val message = statusMessage(TASK_FAILED)
    historyActor ! message

    verify(failureRepo).store(message.appId, TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Store TASK_ERROR") {
    val message = statusMessage(TASK_ERROR)
    historyActor ! message

    verify(failureRepo).store(message.appId, TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Store TASK_LOST") {
    val message = statusMessage(TASK_LOST)
    historyActor ! message

    verify(failureRepo).store(message.appId, TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Ignore TASK_RUNNING") {
    val message = statusMessage(TASK_RUNNING)
    historyActor ! message

    verify(failureRepo, times(0)).store(any(), any())
  }

  test("Ignore TASK_FINISHED") {
    val message = statusMessage(TASK_FINISHED)
    historyActor ! message

    verify(failureRepo, times(0)).store(any(), any())
  }

  test("Ignore TASK_KILLED") {
    val message = statusMessage(TASK_KILLED)
    historyActor ! message

    verify(failureRepo, times(0)).store(any(), any())
  }

  test("Ignore TASK_STAGING") {
    val message = statusMessage(TASK_STAGING)
    historyActor ! message

    verify(failureRepo, times(0)).store(any(), any())
  }

  private def statusMessage(state: TaskState) = {
    val ipAddress: NetworkInfo.IPAddress =
      NetworkInfo.IPAddress
        .newBuilder()
        .setIpAddress("123.123.123.123")
        .setProtocol(NetworkInfo.Protocol.IPv4)
        .build()

    MesosStatusUpdateEvent(
      slaveId = "slaveId",
      taskId = "taskId",
      taskStatus = state.name(),
      message = "message",
      appId = "appId".toPath,
      host = "host",
      ipAddresses = Seq(ipAddress),
      ports = Nil,
      version = Timestamp.now().toString
    )
  }
}
