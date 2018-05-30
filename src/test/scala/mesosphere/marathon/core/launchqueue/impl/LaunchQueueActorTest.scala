package mesosphere.marathon
package core.launchqueue.impl

import akka.Done
import akka.actor.{Actor, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActorRef}
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdated}
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfo
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec, Timestamp}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, PathId, RunSpec, Timestamp}
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
      instanceTracker.list(any)(any) returns Future.successful(Seq.empty)
      instanceTracker.schedule(any[Seq[Instance]])(any) returns Future.successful(Done)
      launchQueue.ask(LaunchQueueDelegate.Add(app, 3)).futureValue
      launchQueue.underlyingActor.launchers should have size 1

      When("An InstanceChange is send to the task launcher actor")
      launchQueue ! instanceUpdate

      Then("A Done is send as well, but this time the answer comes from the LauncherActor")
      expectMsg(Done)
      val launcher = launchQueue.underlyingActor.launchers(app.id)
      launcher ! "GetChanges"
      expectMsg(List(instanceUpdate))
    }

    class Fixture {
      val config = new ScallopConf(Seq.empty) with LaunchQueueConfig {
        verify()
      }
      def runSpecActorProps(runSpec: RunSpec) = Props(new TestLauncherActor) // linter:ignore UnusedParameter
      val app = AppDefinition(PathId("/foo"))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()

      val instanceTracker = mock[InstanceTracker]
      val instanceUpdate = InstanceUpdated(instance, None, Seq.empty)
      val instanceInfo = QueuedInstanceInfo(app, true, 1, 1, Timestamp.now(), Timestamp.now())
      val launchQueue = TestActorRef[LaunchQueueActor](LaunchQueueActor.props(config, Actor.noSender, instanceTracker, runSpecActorProps))

      // Mock the behaviour of the TaskLauncherActor
      class TestLauncherActor extends Actor {
        var changes = List.empty[InstanceChange]
        override def receive: Receive = {
          case TaskLauncherActor.Sync(_, _) => sender() ! instanceInfo
          case TaskLauncherActor.GetCount => sender() ! instanceInfo
          case change: InstanceChange =>
            changes = change :: changes
            sender() ! Done
          case "GetChanges" => sender() ! changes // not part of the LauncherActor protocol. Only used to verify changes.
        }
      }
    }
  }
}
