package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.state.{AppDefinition, PathId}

class ReviveOffersStreamLogicTest extends AkkaUnitTest {
  import ReviveOffersStreamLogic.{Delayed, NotDelayed}
  val webApp = AppDefinition(id = PathId("/test"))
  val monitoringApp = AppDefinition(id = PathId("/test2"))

  val suppressReviveFlow: Flow[Either[InstanceChangeOrSnapshot, ReviveOffersStreamLogic.DelayedStatus], Op, NotUsed] =
    ReviveOffersStreamLogic
      .reviveStateFromInstancesAndDelays
      .via(ReviveOffersStreamLogic.suppressOrReviveFromDiff)
      .via(ReviveOffersStreamLogic.deduplicateSuppress)

  "Vanilla suppress and revive logic" should {
    "issue a suppress in response to an empty snapshot with no updates" in {
      val results = Source(List(Left(InstancesSnapshot(Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue

      results shouldBe Vector(Suppress)
    }

    "emit three revives for a snapshot with multiple instances to launch" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)

      val results = Source(List(Left(InstancesSnapshot(Seq(instance1, instance2)))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Revive, Revive, Revive)
    }

    "emit a revive for each new scheduled instance added" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Nil)),
          Left(InstanceUpdated(instance1, None, Nil)),
          Left(InstanceUpdated(instance2, None, Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Suppress, Revive, Revive, Revive, Revive, Revive, Revive)
    }

    "does not emit a revive for updates to existing scheduled instances" in {
      val instance1 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Nil)),
          Left(InstanceUpdated(instance1, None, Nil)),
          Left(InstanceUpdated(instance1, None, Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Suppress, Revive, Revive, Revive)
    }

    "does not revive if an instance is backed off" in {
      val instance1 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Nil)),
          Right(Delayed(webApp.configRef)),
          Left(InstanceUpdated(instance1, None, Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Suppress)
    }

    "suppresses if an instance becomes backed off, and re-revives when it is available again" in {
      val instance1 = Instance.scheduled(webApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Nil)),
          Left(InstanceUpdated(instance1, None, Nil)),
          Right(Delayed(webApp.configRef)),
          Right(NotDelayed(webApp.configRef))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Suppress, Revive, Revive, Revive, Suppress, Revive, Revive, Revive)
    }

    "does not suppress if a backoff occurs for one instance, but there is still a scheduled instance" in {
      val webInstance = Instance.scheduled(webApp)
      val monitoringInstance = Instance.scheduled(monitoringApp)

      val results = Source(
        List(
          Left(InstancesSnapshot(Seq(webInstance, monitoringInstance))),
          Right(Delayed(webApp.configRef))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)
        .futureValue
      results shouldBe Vector(Revive, Revive, Revive)
    }
  }
}
