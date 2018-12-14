package mesosphere.marathon
package core.launchqueue.impl

import akka.{Done, NotUsed}
import akka.actor.{Actor, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.testkit.ImplicitSender
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdateEffect, InstanceUpdateOperation, InstanceUpdated}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec}
import mesosphere.marathon.stream.EnrichedSource
import org.rogach.scallop.ScallopConf

import scala.concurrent.Future
import scala.concurrent.duration._

class LaunchQueueActorTest extends AkkaUnitTest with ImplicitSender {

  implicit val timeout = Timeout(1.seconds)

  "LaunchQueueActor" should {
    "InstanceChange message is answered with Done, if there is no launcher actor" in new Fixture {
      Given("A LaunchQueueActor without any task launcher")

      When("An InstanceChange is send to the task launcher actor")
      launchQueue ! instanceUpdate

      Then("A Done is send directly from the LaunchQueue")
      expectMsg(Done)
    }

    "InstanceChange message is answered with Done, if there is a launcher actor" in new Fixture {
      Given("A LaunchQueueActor with a task launcher for app /foo")
      instanceTracker.process(any[InstanceUpdateOperation]) returns Future.successful[InstanceUpdateEffect](InstanceUpdateEffect.Noop(null))
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      instanceTracker.specInstances(any[PathId])(any) returns Future.successful(Seq.empty)
      launchQueue.ask(LaunchQueueDelegate.Add(app, 3)).futureValue

      When("An InstanceChange is send to the task launcher actor")
      launchQueue ! instanceUpdate

      Then("A Done is send as well, but this time the answer comes from the LauncherActor")
      expectMsg(Done)
      changes should be (List(instanceUpdate))
    }

    class Fixture {
      val config = new ScallopConf(Seq.empty) with LaunchQueueConfig {
        verify()
      }
      def runSpecActorProps(runSpec: RunSpec) = Props(new TestLauncherActor) // linter:ignore UnusedParameter
      val app = AppDefinition(PathId("/foo"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      val instanceTracker = mock[InstanceTracker]
      instanceTracker.instancesBySpec().returns(Future.successful(InstanceTracker.InstancesBySpec.empty))
      val instanceUpdate = InstanceUpdated(instance, None, Seq.empty)
      val groupManager = mock[GroupManager]
      groupManager.runSpec(app.id).returns(Some(app))
      val delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed] =
        EnrichedSource.emptyCancellable.mapMaterializedValue { _ => NotUsed }
      val launchQueue = system.actorOf(
        LaunchQueueActor.props(
          config, instanceTracker, groupManager, runSpecActorProps, delayUpdates))

      @volatile
      var changes = List.empty[InstanceChange]

      // Mock the behaviour of the TaskLauncherActor
      class TestLauncherActor extends Actor {
        override def receive: Receive = {
          case change: InstanceChange =>
            changes = change :: changes
            sender() ! Done
        }
      }
    }
  }
}
