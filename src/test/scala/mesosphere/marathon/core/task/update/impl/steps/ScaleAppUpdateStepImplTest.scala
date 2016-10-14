package mesosphere.marathon.core.task.update.impl.steps

import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit.TestProbe
import com.google.inject.Provider
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.update.InstanceUpdated
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

class ScaleAppUpdateStepImplTest extends FunSuite with Matchers with GivenWhenThen with Mockito with ScalaFutures {

  implicit lazy val system = ActorSystem()

  test("ScaleAppUpdateStep should only send one ScaleRunSpec when receiving multiple failed tasks") {
    val f = new Fixture

    Given("an instance with terminal containers")
    val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
      .addTaskUnreachable(containerName = Some("unreachable1"))
      .getInstance()

    When("process a task_failed update")
    val failedUpdate1 = f.makeFailedUpdateOp(instance, Some(Condition.Running), Condition.Failed)
    f.step.process(failedUpdate1)

    Then("a scale request is sent to the scheduler actor")
    val answer = f.schedulerActor.expectMsgType[ScaleRunSpec]
    answer.runSpecId should be (instance.instanceId.runSpecId)

    Then("process a task_failed again")
    val failedUpdate2 = f.makeFailedUpdateOp(instance, Some(Condition.Failed), Condition.Failed)
    f.step.process(failedUpdate2)
    f.schedulerActor.expectNoMsg()
  }

  test("ScaleAppUpdateStep should send one ScaleRunSpec if task is directly failed without lastState") {
    val f = new Fixture

    Given("an instance with terminal containers")
    val instance = TestInstanceBuilder.newBuilder(PathId("/app"))
      .addTaskUnreachable(containerName = Some("unreachable1"))
      .getInstance()

    When("process a task_failed update for a task with no last state")
    val failedUpdate1 = f.makeFailedUpdateOp(instance, None, Condition.Failed)
    f.step.process(failedUpdate1)

    Then("a scale request is sent to the scheduler actor")
    val answer = f.schedulerActor.expectMsgType[ScaleRunSpec]
    answer.runSpecId should be (instance.instanceId.runSpecId)

    Then("no more messages are processed")
    f.schedulerActor.expectNoMsg()
  }

  class Fixture {
    val schedulerActor: TestProbe = TestProbe()
    val schedulerActorProvider = new Provider[ActorRef] {
      override def get(): ActorRef = schedulerActor.ref
    }

    def makeFailedUpdateOp(instance: Instance, lastCondition: Option[Condition], newCondition: Condition) =
      InstanceUpdated(instance.copy(state = instance.state.copy(condition = newCondition)), lastCondition.map(state => Instance.InstanceState(state, Timestamp.now(), Some(true))), Seq.empty[MarathonEvent])

    val step = new ScaleAppUpdateStepImpl(schedulerActorProvider)
  }
}
