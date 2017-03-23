package mesosphere.marathon
package core.launcher.impl

import org.apache.mesos.Protos.TaskStatus
import scala.concurrent.{ Future, Promise }

import akka.stream.scaladsl.{ Sink, Source }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.util.state.FrameworkId
import org.scalatest.Inside

class UnreachableReservedOfferMonitorTest extends AkkaUnitTest with Inside {
  import mesosphere.marathon.state.PathId._
  val myFrameworkId = FrameworkId("my-framework")

  val lookupNone =
    Map.empty[Instance.Id, Future[Option[Instance]]].withDefaultValue(Future.successful(None))

  val appId = "/test".toRootPath
  val agentInfo = AgentInfo("host", Some("deadbeef-S01"), Nil)

  def reservationFor(instance: Instance, frameworkId: FrameworkId = myFrameworkId) = {
    val labels = TaskLabels.labelsForTask(frameworkId, instance.tasksMap.values.head.taskId).labels
    MarathonTestHelper.reservation(principal = "marathon", labels)
  }

  def reservedResource(instance: Instance, frameworkId: FrameworkId = myFrameworkId) = {
    MarathonTestHelper.scalarResource(
      "disk", 2, role = "marathon", reservation = Some(reservationFor(instance, frameworkId)))
  }

  def lookupForInstances(instances: Seq[Instance]) =
    instances.map { instance =>
      instance.instanceId -> Future.successful(Option(instance))
    }.toMap.withDefaultValue(Future.successful(None))

  def taskFor(instance: Instance) = instance.tasksMap.values.head

  def newBuilder =
    TestInstanceBuilder.newBuilder(appId).withAgentInfo(agentInfo)

  val runningInstance = newBuilder.addTaskResidentLaunched().getInstance
  val unreachableInstance = newBuilder.addTaskResidentUnreachable().getInstance

  def reservedOffer(instances: Seq[Instance]) = {
    val b = MarathonTestHelper.makeBasicOffer(role = "marathon").clearResources()
    instances.foreach { i => b.addResources(reservedResource(i)) }
    b.setFrameworkId(myFrameworkId.toProto)
    b
  }

  "yields a TASK_GONE for unreachable instances, and nothing for running" in {
    val offerForBothTasks =
      reservedOffer(Seq(unreachableInstance, runningInstance)).build

    val result = Source(List(offerForBothTasks))
      .via(
        UnreachableReservedOfferMonitor.monitorFlow(
          lookupInstance = lookupForInstances(Seq(runningInstance, unreachableInstance))))
      .runWith(Sink.seq)
      .futureValue

    inside(result) {
      case Seq(taskGoneUpdate) =>
        taskGoneUpdate.getTaskId.getValue shouldBe taskFor(unreachableInstance).taskId.idString
    }
  }

  "ignores other framework Ids" in {
    val offerForBothTasks =
      reservedOffer(Seq(unreachableInstance)).build

    val otherOffer = offerForBothTasks.toBuilder
      .setFrameworkId(
        FrameworkId("not-the-tasks-framework-id").toProto)
      .build

    val result = Source(List(otherOffer))
      .via(
        UnreachableReservedOfferMonitor.monitorFlow(
          lookupInstance = lookupForInstances(Seq(runningInstance, unreachableInstance))))
      .runWith(Sink.seq)
      .futureValue

    result shouldBe Nil
  }

  "ignores unknown instances" in {
    val offerForBothTasks =
      reservedOffer(Seq(unreachableInstance)).setFrameworkId(FrameworkId("some-other-framework").toProto).build

    val result = Source(List(offerForBothTasks))
      .via(
        UnreachableReservedOfferMonitor.monitorFlow(
          lookupInstance = Map.empty))
      .runWith(Sink.seq)
      .futureValue

    result shouldBe Nil
  }

  "handles lookup exceptions" in {
    val leTaskStatus = Promise[TaskStatus]
    val input = UnreachableReservedOfferMonitor.run(
      lookupInstance = Map(
        runningInstance.instanceId -> Future.failed(new RuntimeException("very failure")),
        unreachableInstance.instanceId -> Future.successful(Some(unreachableInstance))),
      taskStatusPublisher = { status =>
        leTaskStatus.success(status)
        Future.successful(())
      }
    )

    input.offer(reservedOffer(Seq(unreachableInstance, runningInstance)).build)
    input.complete()

    leTaskStatus.future.futureValue.getTaskId.getValue shouldBe taskFor(unreachableInstance).taskId.idString
  }
}
