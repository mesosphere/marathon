package mesosphere.marathon
package core.history.impl

import akka.actor.Props
import akka.testkit.{ImplicitSender, TestActorRef}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.{MesosStatusUpdateEvent, UnhealthyInstanceKillEvent}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{PathId, TaskFailure, Timestamp}
import mesosphere.marathon.storage.repository.TaskFailureRepository
import org.apache.mesos.Protos.{NetworkInfo, TaskState}

import scala.collection.immutable.Seq

class HistoryActorTest extends AkkaUnitTest with ImplicitSender {
  import org.apache.mesos.Protos.TaskState._

  case class Fixture(failureRepo: TaskFailureRepository = mock[TaskFailureRepository]) {
    val historyActor: TestActorRef[HistoryActor] = TestActorRef(Props(new HistoryActor(system.eventStream, failureRepo)))
  }

  private val runSpecId = PathId("/test")

  private def statusMessage(state: TaskState) = {
    val ipAddress: NetworkInfo.IPAddress =
      NetworkInfo.IPAddress
        .newBuilder()
        .setIpAddress("123.123.123.123")
        .setProtocol(NetworkInfo.Protocol.IPv4)
        .build()

    val instanceId = Instance.Id.forRunSpec(runSpecId)

    MesosStatusUpdateEvent(
      slaveId = "slaveId",
      taskId = Task.Id(instanceId),
      taskStatus = state,
      message = "message",
      appId = runSpecId,
      host = "host",
      ipAddresses = Seq(ipAddress),
      ports = Nil,
      version = Timestamp.now().toString
    )
  }

  private def unhealthyInstanceKilled() = {
    val instanceId = Instance.Id.forRunSpec(runSpecId)
    val taskId = Task.Id(instanceId)
    UnhealthyInstanceKillEvent(
      appId = runSpecId,
      taskId = taskId,
      instanceId = instanceId,
      version = Timestamp(1024),
      reason = "unknown",
      host = "localhost",
      slaveId = None
    )
  }

  "HistoryActor" should {
    "Store TASK_FAILED" in new Fixture {
      val message = statusMessage(TASK_FAILED)
      historyActor ! message

      verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
    }

    "Store TASK_ERROR" in new Fixture {
      val message = statusMessage(TASK_ERROR)
      historyActor ! message

      verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
    }

    "Store TASK_LOST" in new Fixture {
      val message = statusMessage(TASK_LOST)
      historyActor ! message

      verify(failureRepo).store(TaskFailure.FromMesosStatusUpdateEvent(message).get)
    }

    "Ignore TASK_RUNNING" in new Fixture {
      val message = statusMessage(TASK_RUNNING)
      historyActor ! message

      verify(failureRepo, times(0)).store(any)
    }

    "Ignore TASK_FINISHED" in new Fixture {
      val message = statusMessage(TASK_FINISHED)
      historyActor ! message

      verify(failureRepo, times(0)).store(any)
    }

    "Ignore TASK_KILLED" in new Fixture {
      val message = statusMessage(TASK_KILLED)
      historyActor ! message

      verify(failureRepo, times(0)).store(any)
    }

    "Ignore TASK_STAGING" in new Fixture {
      val message = statusMessage(TASK_STAGING)
      historyActor ! message

      verify(failureRepo, times(0)).store(any)
    }

    "Store UnhealthyTaskKilled" in new Fixture {
      val message = unhealthyInstanceKilled()
      historyActor ! message

      verify(failureRepo).store(TaskFailure.FromUnhealthyInstanceKillEvent(message))
    }
  }
}
