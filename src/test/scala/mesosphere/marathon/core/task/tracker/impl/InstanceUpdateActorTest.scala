package mesosphere.marathon
package core.task.tracker.impl

import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.{ActorRef, Status, Terminated}
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOpResolver, InstanceUpdateOperation}
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.{StateChanged, UpdateContext}
import mesosphere.marathon.integration.setup.WaitTestSupport
import mesosphere.marathon.state.{PathId, Timestamp}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class InstanceUpdateActorTest extends AkkaUnitTest {
  import scala.concurrent.duration._
  override lazy val akkaConfig =
    ConfigFactory.parseString(""" akka.actor.guardian-supervisor-strategy = "akka.actor.StoppingSupervisorStrategy" """)
      .withFallback(ConfigFactory.load())

  "InstanceUpdateActor" should {
    "escalate process failures" in {
      val f = new Fixture

      Given("an update operation")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val updateOperation = UpdateContext(Timestamp.now().+(1.day), InstanceUpdateOperation.ForceExpunge(instanceId))

      When("the op is passed to the actor for processing")
      f.updateActor.receive(updateOperation)
      f.instanceTrackerActorReplyWith(Status.Failure(new RuntimeException("failed")))

      Then("the exception is escalated and the actor dies")
      watch(f.updateActor)
      expectMsgClass(classOf[Terminated]).getActor should equal(f.updateActor)

      And("there are no more interactions")
      f.instanceTrackerActor.msgAvailable should be(false)
    }

    "check process timeouts" in {
      val f = new Fixture

      Given("an operation with an already reached deadline")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val op = UpdateContext(f.clock.now(), InstanceUpdateOperation.ForceExpunge(instanceId))

      When("the op is passed to the actor for processing")
      f.updateActor.receive(op, f.opInitiator.ref)

      Then("we the sender gets a timeout exception")
      val failure = f.opInitiator.expectMsgClass(classOf[Status.Failure])
      failure.cause.getClass should be(classOf[TimeoutException])

      And("there are no more interactions")
      f.instanceTrackerActor.msgAvailable should be(false)

      Given("a processor that processes 'anotherOp' immediately")
      val anotherOp = op.copy(deadline = f.oneSecondInFuture)

      When("we process another op, it is not effected")
      f.updateActor.receive(anotherOp)

      Then("process op was called")
      f.instanceTrackerActor.expectMsgAnyClassOf(classOf[StateChanged])
    }

    "process directly first op for a task" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val op = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instanceId))

      When("the op is passed to the actor for processing")
      f.updateActor.receive(op)
      f.instanceTrackerActorReplyWith(Done)

      And("all gauges are zero again")
      f.actorMetrics.numberOfActiveOps.value should be(0)
      f.actorMetrics.numberOfQueuedOps.value should be(0)
    }

    "show currently processed ops in the metrics" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val op = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instanceId))

      When("the op is passed to the actor for processing")
      f.updateActor.receive(op)

      Then("there is one active request and none queued")
      f.actorMetrics.numberOfActiveOps.value should be(1)
      f.actorMetrics.numberOfQueuedOps.value should be(0)

      And("update is finished")
      f.instanceTrackerActorReplyWith(Done)
    }

    "ops for different tasks are processed concurrently" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instance1Id = Instance.Id.forRunSpec(appId)
      val op1 = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instance1Id))
      val instance2Id = Instance.Id.forRunSpec(appId)
      val op2 = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instance2Id))

      When("the ops are passed to the actor for processing")
      f.updateActor.receive(op1)
      f.updateActor.receive(op2)

      Then("there are two active requests and none queued")
      f.actorMetrics.numberOfActiveOps.value should be(2)
      f.actorMetrics.numberOfQueuedOps.value should be(0)

      When("one operation finishes")
      f.instanceTrackerActorReplyWith(Done)

      Then("eventually our active ops count gets decreased")
      WaitTestSupport.waitUntil("actor reacts to op2 finishing", 1.second)(f.actorMetrics.numberOfActiveOps.value == 1)

      And("the second task doesn't have queue anymore")
      f.updateActor.underlyingActor.updatesByInstanceId should have size 1

      And("the first task still does have a queue")
      f.updateActor.underlyingActor.updatesByInstanceId(instance2Id) should have size 1
    }

    "process sequentially ops for the same task" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instance1Id = Instance.Id.forRunSpec(appId)
      val op1 = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instance1Id))
      val op2 = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instance1Id))

      When("the ops are passed to the actor for processing")
      f.updateActor.receive(op1)
      f.updateActor.receive(op2)

      And("there are one active request and one queued")
      f.actorMetrics.numberOfActiveOps.value should be(1)
      f.actorMetrics.numberOfQueuedOps.value should be(1)

      When("one operation finishes")
      f.instanceTrackerActorReplyWith(Done)

      Then("there are one active request and none queued anymore")
      f.actorMetrics.numberOfActiveOps.value should be(1)
      f.actorMetrics.numberOfQueuedOps.value should be(0)

      When("second operation finishes")
      f.instanceTrackerActorReplyWith(Done)

      Then("eventually our active ops count gets decreased")
      WaitTestSupport.waitUntil("actor reacts to op2 finishing", 1.second)(f.actorMetrics.numberOfActiveOps.value == 0)

      And("our queue will be empty")
      f.updateActor.underlyingActor.updatesByInstanceId should be(empty)
    }

    "reply with failure for failed update action" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val op = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instanceId))
      And("failing update operation")
      f.instanceUpdateOpResolver.resolve(any)(any).returns(Future.successful(InstanceUpdateEffect.Failure(new RuntimeException("test"))))

      When("the ops are passed to the actor for processing")
      f.updateActor.receive(op, f.opInitiator.ref)

      Then("Status failure is received")
      f.opInitiator.expectMsgAnyClassOf(classOf[Status.Failure])
    }

    "does not trigger statechange on instancetracker for noop change" in {
      val f = new Fixture

      Given("an op")
      val appId = PathId("/app")
      val instanceId = Instance.Id.forRunSpec(appId)
      val op = UpdateContext(f.oneSecondInFuture, InstanceUpdateOperation.ForceExpunge(instanceId))
      And("failing update operation")
      f.instanceUpdateOpResolver.resolve(any)(any).returns(Future.successful(InstanceUpdateEffect.Noop(instanceId)))

      When("the ops are passed to the actor for processing")
      f.updateActor.receive(op, f.opInitiator.ref)

      Then("Status failure is received")
      f.instanceTrackerActor.expectNoMessage(1.second)
    }
  }

  class Fixture {
    import scala.concurrent.duration.DurationInt

    lazy val clock = new SettableClock()
    lazy val opInitiator = TestProbe()
    lazy val instanceTrackerActor = TestProbe()
    lazy val actorMetrics = new InstanceUpdateActor.ActorMetrics()
    lazy val instanceUpdateOpResolver = mock[InstanceUpdateOpResolver]
    instanceUpdateOpResolver.resolve(any)(any).returns(Future.successful(InstanceUpdateEffect.Update(TestInstanceBuilder.newBuilder(PathId("/app")).instance, None, Seq.empty)))
    lazy val updateActor = TestActorRef(new InstanceUpdateActor(clock, actorMetrics, instanceTrackerActor.ref, instanceUpdateOpResolver, 10.seconds))

    def oneSecondInFuture: Timestamp = clock.now() + 1.second

    def instanceTrackerActorReplyWith(msg: Any): Unit = {
      instanceTrackerActor.expectMsgAnyClassOf(classOf[StateChanged])
      instanceTrackerActor.reply(msg)
    }
  }
}
