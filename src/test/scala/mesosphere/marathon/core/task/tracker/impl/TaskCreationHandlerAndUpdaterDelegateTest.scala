package mesosphere.marathon.core.task.tracker.impl

import akka.actor.Status
import akka.testkit.TestProbe
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.{ TaskID, TaskStatus }
import org.scalatest.{ Matchers, GivenWhenThen }
import org.scalatest.concurrent.ScalaFutures

class TaskCreationHandlerAndUpdaterDelegateTest
    extends MarathonActorSupport with MarathonSpec with Mockito with GivenWhenThen with ScalaFutures with Matchers {

  test("Created succeeds") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)

    When("created is called")
    val create = f.delegate.created(task)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, TaskOpProcessor.Action.Update(task))
    )

    When("the request is acknowledged")
    f.taskTrackerProbe.reply(())
    Then("The reply is the value of task")
    create.futureValue should be(task)
  }

  test("Created fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)

    When("created is called")
    val create = f.delegate.created(task)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, TaskOpProcessor.Action.Update(task))
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    create.failed.futureValue.getMessage should include(appId.toString)
    create.failed.futureValue.getMessage should include(task.taskId.idString)
    create.failed.futureValue.getMessage should include("Update")
    create.failed.futureValue.getCause should be(cause)
  }

  test("Terminated succeeds") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)

    When("created is called")
    val create = f.delegate.terminated(task.taskId)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, TaskOpProcessor.Action.Expunge)
    )

    When("the request is acknowledged")
    f.taskTrackerProbe.reply(())
    Then("The reply is the value of the future")
    create.futureValue should be(())
  }

  test("Terminated fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)

    When("created is called")
    val create = f.delegate.terminated(task.taskId)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, TaskOpProcessor.Action.Expunge)
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    create.failed.futureValue.getMessage should include(appId.toString)
    create.failed.futureValue.getMessage should include(task.taskId.idString)
    create.failed.futureValue.getMessage should include("Expunge")
    create.failed.futureValue.getCause should be(cause)
  }

  test("StatusUpdate succeeds") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val taskId = "task1"

    val update = TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue(taskId)).buildPartial()

    When("created is called")
    val create = f.delegate.statusUpdate(appId, update)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, Task.Id(taskId), TaskOpProcessor.Action.UpdateStatus(update))
    )

    When("the request is acknowledged")
    f.taskTrackerProbe.reply(())
    Then("The reply is the value of the future")
    create.futureValue should be(())
  }

  test("StatusUpdate fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val taskId = Task.Id.forApp(appId)

    val update = TaskStatus.newBuilder().setTaskId(taskId.mesosTaskId).buildPartial()

    When("created is called")
    val create = f.delegate.statusUpdate(appId, update)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, taskId, TaskOpProcessor.Action.UpdateStatus(update))
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    create.failed.futureValue.getMessage should include(appId.toString)
    create.failed.futureValue.getMessage should include(taskId.toString)
    create.failed.futureValue.getMessage should include("UpdateStatus")
    create.failed.futureValue.getCause should be(cause)
  }

  class Fixture {
    lazy val clock = ConstantClock()
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val taskTrackerProbe = TestProbe()
    lazy val delegate = new TaskCreationHandlerAndUpdaterDelegate(clock, config, taskTrackerProbe.ref)
    lazy val timeoutDuration = delegate.timeout.duration
    def timeoutFromNow = clock.now() + timeoutDuration
  }
}
