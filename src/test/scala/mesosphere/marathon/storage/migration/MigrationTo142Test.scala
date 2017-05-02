package mesosphere.marathon
package storage.migration

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior
import mesosphere.marathon.Seq
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.test.GroupCreation

class MigrationTo142Test extends AkkaUnitTest with GroupCreation {
  import MigrationTo142.migrationFlow

  "Migration to 1.4.2" should {
    "do nothing if there are no resident apps" in {
      val result = Source.single(AppDefinition(id = PathId("abc")))
        .via(migrationFlow)
        .runWith(Sink.seq)
        .futureValue

      result.shouldBe(Nil)
    }

    "fix wrong UnreachableStrategy for resident apps" in {
      val badApp = AppDefinition(
        id = PathId("/badApp"),
        residency = Some(Residency(23L, TaskLostBehavior.WAIT_FOREVER)),
        unreachableStrategy = UnreachableStrategy.default(resident = false))
      val goodApp = AppDefinition(id = PathId("/goodApp"))
      val fixedApp = badApp.copy(unreachableStrategy = UnreachableDisabled)

      val result = Source(Seq(badApp, goodApp))
        .via(migrationFlow)
        .runWith(Sink.seq)
        .futureValue

      result shouldBe (Seq(fixedApp))
    }
  }
}
