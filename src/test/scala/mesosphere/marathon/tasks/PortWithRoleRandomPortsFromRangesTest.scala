package mesosphere.marathon
package tasks

import java.util

import mesosphere.UnitTest
import mesosphere.marathon.tasks.PortsMatcher.{ PortRange, PortWithRole }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.util.Random

class PortWithRoleRandomPortsFromRangesTest extends UnitTest {

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] def portRange(role: String, begin: Long, end: Long): PortRange = {
    PortRange(role, begin.toInt, end.toInt)
  }

  private[this] def withRandomSeeds(input: Seq[PortRange], expectedOutput: Seq[PortWithRole]): Unit = {
    for (seed <- 1 to 10) {
      withClue(s"seed = $seed") {
        val rand = new Random(new util.Random(seed.toLong))

        assert(PortWithRole.lazyRandomPortsFromRanges(rand)(input).to[Set] == expectedOutput.to[Set])
      }
    }

  }

  "PortWithRoleRandomPortsFromRanges" should {
    "works for empty seq" in {
      assert(PortWithRole.lazyRandomPortsFromRanges()(Seq.empty).to[Seq] == Seq.empty)
    }

    "works for one element range" in {
      assert(
        PortWithRole.lazyRandomPortsFromRanges()(Seq(portRange("role", 10, 10))).to[Seq] == Seq(PortWithRole("role", 10)))
    }

    "works for one range with four ports" in {
      withRandomSeeds(
        input = Seq(
          portRange("role", 10, 13)
        ),
        expectedOutput = (10 to 13).map(PortWithRole("role", _))
      )
    }

    "works for multiple ranges" in {
      withRandomSeeds(
        input = Seq(
          portRange("role", 10, 13),
          portRange("marathon", 14, 20),
          portRange("role", 21, 30)
        ),
        expectedOutput =
          (10 to 13).map(PortWithRole("role", _)) ++
            (14 to 20).map(PortWithRole("marathon", _)) ++
            (21 to 30).map(PortWithRole("role", _))
      )
    }
  }
}
