package mesosphere.marathon.core.task.tracker.impl

import akka.actor.Status
import akka.testkit.TestProbe
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
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
    val stateOp = TaskStateOp.Create(task)
    val expectedStateChange = TaskStateChange.Update(task)

    When("created is called")
    val create = f.delegate.created(stateOp)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, stateOp)
    )

    When("the request is acknowledged")
    f.taskTrackerProbe.reply(expectedStateChange)
    Then("The reply is Unit, because task updates are deferred")
    create.futureValue should be(())
  }

  test("Created fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)
    val stateOp = TaskStateOp.Create(task)

    When("created is called")
    val create = f.delegate.created(stateOp)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, stateOp)
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    val createValue = create.failed.futureValue
    createValue.getMessage should include(appId.toString)
    createValue.getMessage should include(task.taskId.idString)
    createValue.getMessage should include("Create")
    createValue.getCause should be(cause)
  }

  test("Terminated succeeds") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)
    val stateOp = TaskStateOp.ForceExpunge(task.taskId)
    val expectedStateChange = TaskStateChange.Expunge(task.taskId)

    When("terminated is called")
    val terminated = f.delegate.terminated(stateOp)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, stateOp)
    )

    When("the request is acknowledged")
    f.taskTrackerProbe.reply(expectedStateChange)
    Then("The reply is the value of the future")
    terminated.futureValue should be(expectedStateChange)
  }

  test("Terminated fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)
    val stateOp = TaskStateOp.ForceExpunge(task.taskId)

    When("terminated is called")
    val terminated = f.delegate.terminated(stateOp)

    Then("an expunge operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, task.taskId, stateOp)
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    val terminatedValue = terminated.failed.futureValue
    terminatedValue.getMessage should include(appId.toString)
    terminatedValue.getMessage should include(task.taskId.idString)
    terminatedValue.getMessage should include("Expunge")
    terminatedValue.getCause should be(cause)
  }

  test("StatusUpdate succeeds") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskId = task.taskId
    val taskIdString = taskId.idString
    val now = f.clock.now()

    val update = TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue(taskIdString)).buildPartial()

    When("created is called")
    val statusUpdate = f.delegate.statusUpdate(appId, update)
    val stateOp = TaskStateOp.MesosUpdate(taskId, MarathonTaskStatus(update), now)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, taskId, stateOp)
    )

    When("the request is acknowledged")
    val expectedStateChange = TaskStateChange.Update(task)
    f.taskTrackerProbe.reply(expectedStateChange)
    Then("The reply is the value of the future")
    statusUpdate.futureValue should be(expectedStateChange)
  }

  test("StatusUpdate fails") {
    val f = new Fixture
    val appId: PathId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val now = f.clock.now()

    val update = TaskStatus.newBuilder().setTaskId(taskId.mesosTaskId).buildPartial()

    When("statusUpdate is called")
    val statusUpdate = f.delegate.statusUpdate(appId, update)
    val stateOp = TaskStateOp.MesosUpdate(taskId, MarathonTaskStatus(update), now)

    Then("an update operation is requested")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.ForwardTaskOp(f.timeoutFromNow, taskId, stateOp)
    )

    When("the response is an error")
    val cause: RuntimeException = new scala.RuntimeException("test failure")
    f.taskTrackerProbe.reply(Status.Failure(cause))
    Then("The reply is the value of task")
    val updateValue = statusUpdate.failed.futureValue
    updateValue.getMessage should include(appId.toString)
    updateValue.getMessage should include(taskId.toString)
    updateValue.getMessage should include("MesosUpdate")
    updateValue.getCause should be(cause)
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
