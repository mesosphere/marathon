package mesosphere.marathon.core.history.impl

import akka.actor.{ ActorRef, Props }
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.marathon.core.event.{ MesosStatusUpdateEvent, UnhealthyInstanceKillEvent }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, TaskFailure, Timestamp }
import mesosphere.marathon.storage.repository.TaskFailureRepository
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec }
import org.apache.mesos.Protos.{ NetworkInfo, TaskState }
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfter, Matchers }

import scala.collection.immutable.Seq

class HistoryActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with MockitoSugar
    with BeforeAndAfter
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

    verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Store TASK_ERROR") {
    val message = statusMessage(TASK_ERROR)
    historyActor ! message

    verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Store TASK_LOST") {
    val message = statusMessage(TASK_LOST)
    historyActor ! message

    verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
  }

  test("Ignore TASK_RUNNING") {
    val message = statusMessage(TASK_RUNNING)
    historyActor ! message

    verify(failureRepo, times(0)).store(any())
  }

  test("Ignore TASK_FINISHED") {
    val message = statusMessage(TASK_FINISHED)
    historyActor ! message

    verify(failureRepo, times(0)).store(any())
  }

  test("Ignore TASK_KILLED") {
    val message = statusMessage(TASK_KILLED)
    historyActor ! message

    verify(failureRepo, times(0)).store(any())
  }

  test("Ignore TASK_STAGING") {
    val message = statusMessage(TASK_STAGING)
    historyActor ! message

    verify(failureRepo, times(0)).store(any())
  }

  test("Store UnhealthyTaskKilled") {
    val message = unhealthyInstanceKilled()
    historyActor ! message

    verify(failureRepo).store(TaskFailure.FromUnhealthyInstanceKillEvent(message))
  }

  private val runSpecId = PathId("/test")

  private def statusMessage(state: TaskState) = {
    val ipAddress: NetworkInfo.IPAddress =
      NetworkInfo.IPAddress
        .newBuilder()
        .setIpAddress("123.123.123.123")
        .setProtocol(NetworkInfo.Protocol.IPv4)
        .build()

    MesosStatusUpdateEvent(
      slaveId = "slaveId",
      taskId = Task.Id.forRunSpec(runSpecId),
      taskStatus = state.name(),
      message = "message",
      appId = runSpecId,
      host = "host",
      ipAddresses = Seq(ipAddress),
      ports = Nil,
      version = Timestamp.now().toString
    )
  }

  private def unhealthyInstanceKilled() = {
    val taskId = Task.Id.forRunSpec(runSpecId)
    UnhealthyInstanceKillEvent(
      appId = runSpecId,
      taskId = taskId,
      instanceId = taskId.instanceId,
      version = Timestamp(1024),
      reason = "unknown",
      host = "localhost",
      slaveId = None
    )
  }
}
