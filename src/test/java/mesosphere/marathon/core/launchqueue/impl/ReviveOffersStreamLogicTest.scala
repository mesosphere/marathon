package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.DelayedStatus
import mesosphere.marathon.state.{AppDefinition, PathId}

import scala.concurrent.duration._

class ReviveOffersStreamLogicTest extends AkkaUnitTest {
  import ReviveOffersStreamLogic.{Delayed, NotDelayed}
  val webApp = AppDefinition(id = PathId("/test"))
  val monitoringApp = AppDefinition(id = PathId("/test2"))

  val inputSourceQueue = Source.queue[Either[InstanceChangeOrSnapshot, DelayedStatus]](16, OverflowStrategy.fail)
  val outputSinkQueue = Sink.queue[Op]()

  "Suppress and revive" should {
    "combine 3 revive-worth events received within the throttle window in to a two throttle events" in {
      val instance1 = Instance.scheduled(webApp)
      val instance2 = Instance.scheduled(webApp)
      val instance3 = Instance.scheduled(webApp)

      val suppressReviveFlow = ReviveOffersStreamLogic.suppressAndReviveFlow(minReviveOffersInterval = 200.millis, enableSuppress = true)

      val (input, output) = inputSourceQueue.via(suppressReviveFlow).toMat(outputSinkQueue)(Keep.both).run

      input.offer(Left(InstanceUpdated(instance1, None, Nil))).futureValue
      input.offer(Left(InstanceUpdated(instance2, None, Nil))).futureValue
      input.offer(Left(InstanceUpdated(instance3, None, Nil))).futureValue
      input.complete()

      // revives from the first instance
      Seq.fill(3){ output.pull().futureValue }.flatten shouldBe Seq(Revive, Revive, Revive)

      // set of revives for instance 2 and 3
      Seq.fill(3){ output.pull().futureValue }.flatten shouldBe Seq(Revive, Revive, Revive)

      output.pull().futureValue shouldBe None // should be EOS
    }

    "drops suppress elements if enableSuppress is disabled" in {
      val suppressReviveFlow = ReviveOffersStreamLogic.suppressAndReviveFlow(minReviveOffersInterval = 200.millis, enableSuppress = false)

      val result = Source(List(Left(InstancesSnapshot(Nil))))
        .via(suppressReviveFlow)
        .runWith(Sink.seq)

      result.futureValue shouldBe Nil
    }
  }

  "Suppress and revive without throttling" should {
    // Many of these components are more easily tested without throttling logic
    val suppressReviveFlow: Flow[Either[InstanceChangeOrSnapshot, ReviveOffersStreamLogic.DelayedStatus], Op, NotUsed] =
      ReviveOffersStreamLogic
        .reviveStateFromInstancesAndDelays
        .via(ReviveOffersStreamLogic.suppressOrReviveFromDiff)
        .via(ReviveOffersStreamLogic.deduplicateSuppress)

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
