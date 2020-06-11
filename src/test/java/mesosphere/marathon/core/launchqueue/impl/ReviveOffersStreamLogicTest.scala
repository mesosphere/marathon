package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.{DelayedStatus, IssueRevive, RoleDirective, UpdateFramework}
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}
import org.scalatest.Inside

import scala.concurrent.Future
import scala.concurrent.duration._

class ReviveOffersStreamLogicTest extends AkkaUnitTest with Inside {

  import ReviveOffersStreamLogic.{Delayed, NotDelayed}

  val webApp = AppDefinition(id = AbsolutePathId("/test"), role = "web")
  val monitoringApp = AppDefinition(id = AbsolutePathId("/test2"), role = "monitoring")

  val inputSourceQueue = Source.queue[Either[InstanceChangeOrSnapshot, DelayedStatus]](16, OverflowStrategy.fail)
  val outputSinkQueue = Sink.queue[RoleDirective]()
  val launchedInstance = TestInstanceBuilder.newBuilderForRunSpec(webApp).addTaskLaunched().instance

  "Suppress and revive" should {
    "combine 3 revive-worth events received within the throttle window in to a two throttle events" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)
      val instance3 = Instance.scheduled(webApp)

      Given("A suppress/revive flow with suppression enabled and 200 millis revive interval")
      val suppressReviveFlow =
        ReviveOffersStreamLogic.suppressAndReviveFlow(minReviveOffersInterval = 200.millis, enableSuppress = true, defaultRole = "web")

      val (input, output) = inputSourceQueue.via(suppressReviveFlow).toMat(outputSinkQueue)(Keep.both).run

      When("An initial snapshot with a launched instance is offered")
      input.offer(Left(InstancesSnapshot(List(launchedInstance)))).futureValue

      Then("An update framework event is issued with the role suppressed in response to the snapshot")
      inside(output.pull().futureValue) {
        case Some(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersNotWanted)
          newlyRevived shouldBe Set.empty
          newlySuppressed shouldBe Set.empty
      }

      And("3 instance updates are sent for the role 'web'")
      Future
        .sequence(Seq(instance1, instance2, instance3).map { i =>
          input.offer(Left(InstanceUpdated(i, None, Nil)))
        })
        .futureValue

      Then("The revives from the instances get combined in to a single update framework call")
      inside(output.pull().futureValue) {
        case Some(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersWanted)
          newlyRevived shouldBe Set("web")
          newlySuppressed shouldBe Set.empty
      }

      And("the revive is eventually repeated")
      inside(output.pull().futureValue) {
        case Some(IssueRevive(roles)) =>
          roles shouldBe Set("web")
      }

      When("the stream is closed")
      input.complete()

      Then("no further events are emitted")
      output.pull().futureValue shouldBe None // should be EOS
    }

    "does not send suppress if enableSuppress is disabled" in {
      val suppressReviveFlow =
        ReviveOffersStreamLogic.suppressAndReviveFlow(minReviveOffersInterval = 200.millis, enableSuppress = false, defaultRole = "web")

      val result = Source(List(Left(InstancesSnapshot(List(launchedInstance)))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(result) {
        case Seq(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersWanted)
          newlyRevived shouldBe Set("web")
          newlySuppressed shouldBe Set.empty
      }
    }
  }

  "ReviveRepeaterLogic" should {
    "send a repeat after the second tick for roles newly revived by UpdateFramework" in {
      val logic = new ReviveOffersStreamLogic.ReviveRepeaterLogic

      logic.processRoleDirective(UpdateFramework(Map("role" -> OffersWanted), Set("role"), Set.empty))

      logic.handleTick() shouldBe Nil
      logic.handleTick() shouldBe List(IssueRevive(Set("role")))
    }

    "does not repeat revives for roles that become suppressed" in {
      val logic = new ReviveOffersStreamLogic.ReviveRepeaterLogic

      logic.processRoleDirective(UpdateFramework(Map("role" -> OffersWanted), Set("role"), Set.empty))
      logic.handleTick() shouldBe Nil

      logic.processRoleDirective(UpdateFramework(Map("role" -> OffersNotWanted), Set.empty, Set("role")))

      logic.handleTick() shouldBe Nil
      logic.handleTick() shouldBe Nil

    }

    "send a repeat revive once after the second tick" in {
      val logic = new ReviveOffersStreamLogic.ReviveRepeaterLogic

      logic.processRoleDirective(UpdateFramework(Map("role" -> OffersWanted), Set("role"), Set.empty))
      logic.handleTick() shouldBe Nil

      // First repeat for update framework
      logic.handleTick() shouldBe List(IssueRevive(Set("role")))

      // Revive was triggered
      logic.processRoleDirective(IssueRevive(Set("role")))
      logic.handleTick() shouldBe Nil

      // Second repeat for newly triggered revive
      logic.handleTick() shouldBe List(IssueRevive(Set("role")))
      logic.handleTick() shouldBe Nil
    }
  }

  "Suppress and revive without throttling" should {
    // Many of these components are more easily tested without throttling logic
    val suppressReviveFlow: Flow[Either[InstanceChangeOrSnapshot, ReviveOffersStreamLogic.DelayedStatus], RoleDirective, NotUsed] =
      ReviveOffersStreamLogic
        .reviveStateFromInstancesAndDelays("web")
        .map(_.roleReviveVersions)
        .via(ReviveOffersStreamLogic.reviveDirectiveFlow(enableSuppress = true))

    "issues a suppress for the default role in response to an empty snapshot" in {
      val results = Source(List(Left(InstancesSnapshot(Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(results) {
        case Seq(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersNotWanted)
          newlyRevived shouldBe Set.empty
          newlySuppressed shouldBe Set.empty
      }
    }

    "emit a single revive for a snapshot with multiple instances to launch" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)

      val results = Source(List(Left(InstancesSnapshot(Seq(instance1, instance2)))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      inside(results) {
        case Seq(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersWanted)
          newlyRevived shouldBe Set("web")
          newlySuppressed shouldBe Set.empty
      }
    }

    "emit a revive for each new scheduled instance added" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(List(launchedInstance))),
          Left(InstanceUpdated(instance1, None, Nil)),
          Left(InstanceUpdated(instance2, None, Nil))
        )
      ).via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(results) {
        case Seq(updateFramework: UpdateFramework, updateToReviveForFirstInstance: UpdateFramework, reviveForSecondInstance: IssueRevive) =>
          updateFramework.roleState shouldBe Map("web" -> OffersNotWanted)

          updateToReviveForFirstInstance.roleState shouldBe Map("web" -> OffersWanted)
          updateToReviveForFirstInstance.newlyRevived shouldBe Set("web")

          reviveForSecondInstance.roles shouldBe Set("web")
      }
    }

    "does not emit a new revive for updates to existing scheduled instances" in {
      val instance1 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(List(launchedInstance))),
          Left(InstanceUpdated(instance1, None, Nil)),
          Left(InstanceUpdated(instance1, None, Nil))
        )
      ).via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(results) {
        case Seq(updateFramework: UpdateFramework, updateToReviveForFirstInstance: UpdateFramework) =>
          updateFramework.roleState shouldBe Map("web" -> OffersNotWanted)

          updateToReviveForFirstInstance.roleState shouldBe Map("web" -> OffersWanted)
          updateToReviveForFirstInstance.newlyRevived shouldBe Set("web")
      }
    }

    "does not revive if an instance is backed off" in {
      val instance1 = Instance.scheduled(webApp)

      val results =
        Source(List(Left(InstancesSnapshot(Nil)), Right(Delayed(webApp.configRef)), Left(InstanceUpdated(instance1, None, Nil))))
          .via(suppressReviveFlow)
          .runWith(Sink.seq)
          .futureValue

      inside(results) {
        case Seq(UpdateFramework(roleState, newlyRevived, newlySuppressed)) =>
          roleState shouldBe Map("web" -> OffersNotWanted)
      }
    }

    "suppresses if an instance becomes backed off, and re-revives when it is available again" in {
      val instance1 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Nil)),
          Left(InstanceUpdated(instance1, None, Nil)),
          Right(Delayed(webApp.configRef)),
          Right(NotDelayed(webApp.configRef))
        )
      ).via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(results) {
        case Seq(initialUpdate: UpdateFramework, update1: UpdateFramework, update2: UpdateFramework, update3: UpdateFramework) =>
          initialUpdate.roleState("web") shouldBe OffersNotWanted
          update1.roleState("web") shouldBe OffersWanted
          update2.roleState("web") shouldBe OffersNotWanted
          update3.roleState("web") shouldBe OffersWanted
      }
    }

    "does not suppress if a backoff occurs for one instance, but there is still a scheduled instance" in {
      val webInstance = Instance.scheduled(webApp)
      val monitoringInstance = Instance.scheduled(monitoringApp)

      val results = Source(List(Left(InstancesSnapshot(Seq(webInstance, monitoringInstance))), Right(Delayed(webApp.configRef))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      inside(results) {
        case Seq(update1: UpdateFramework, update2: UpdateFramework) =>
          update1.roleState shouldBe Map("monitoring" -> OffersWanted, "web" -> OffersWanted)

          update2.roleState shouldBe Map("monitoring" -> OffersWanted, "web" -> OffersNotWanted)
      }
    }
  }
}
