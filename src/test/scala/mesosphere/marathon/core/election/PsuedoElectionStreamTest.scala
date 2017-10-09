package mesosphere.marathon
package core.election

import akka.stream.scaladsl.{ Keep, Sink }
import mesosphere.AkkaUnitTest

class PsuedoElectionStreamTest extends AkkaUnitTest {
  "it emits an elected event right away" in {
    PsuedoElectionStream()
      .take(1)
      .runWith(Sink.seq)
      .futureValue shouldBe Seq(LeadershipState.ElectedAsLeader)
  }

  "it does not emit a LeadershipState.StandbyEvent unless the stream is cancelled" in {
    val stream1 = PsuedoElectionStream()
      .takeWithin(patienceConfig.interval)
      .runWith(Sink.seq)

    val (stream2Cancel, stream2) = PsuedoElectionStream()
      .toMat(Sink.seq)(Keep.both)
      .run
    stream2Cancel.cancel()

    stream1.futureValue shouldBe Seq(LeadershipState.ElectedAsLeader)
    stream2.futureValue shouldBe Seq(LeadershipState.ElectedAsLeader, LeadershipState.Standby(None))
  }
}
