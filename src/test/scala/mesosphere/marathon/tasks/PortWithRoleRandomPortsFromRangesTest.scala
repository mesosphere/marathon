package mesosphere.marathon.tasks

import java.util

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.tasks.PortsMatcher.{ PortRange, PortWithRole }
import scala.collection.immutable.Seq
import org.apache.mesos.Protos

import scala.util.Random

class PortWithRoleRandomPortsFromRangesTest extends MarathonSpec {

  private[this] def portRange(role: String, begin: Long, end: Long): PortRange = {
    val range = Protos.Value.Range.newBuilder().setBegin(begin).setEnd(end).build()
    PortRange(role, range)
  }

  private[this] def withRandomSeeds(input: Seq[PortRange], expectedOutput: Iterable[PortWithRole]): Unit = {
    for (seed <- 1 to 10) {
      withClue(s"seed = $seed") {
        val rand = new Random(new util.Random(seed.toLong))

        assert(PortWithRole.randomPortsFromRanges(rand)(input).to[Set] == expectedOutput.to[Set])
      }
    }

  }

  test("works for empty seq") {
    assert(PortWithRole.randomPortsFromRanges()(Seq.empty).to[Seq] == Seq.empty)
  }

  test("works for one element range") {
    assert(
      PortWithRole.randomPortsFromRanges()(Seq(portRange("role", 10, 10))).to[Seq] == Seq(PortWithRole("role", 10)))
  }

  test("works for one range with four ports") {
    withRandomSeeds(
      input = Seq(
        portRange("role", 10, 13)
      ),
      expectedOutput = (10 to 13).map(PortWithRole("role", _))
    )
  }

  test("works for multiple ranges") {
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
