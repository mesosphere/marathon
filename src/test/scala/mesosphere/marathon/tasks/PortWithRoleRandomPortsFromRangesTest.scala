package mesosphere.marathon.tasks

import java.util
import java.util.concurrent.TimeUnit

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.tasks.PortsMatcher.{ PortRange, PortWithRole }
import org.slf4j.LoggerFactory
import scala.collection.immutable.Seq
import org.apache.mesos.Protos

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

class PortWithRoleRandomPortsFromRangesTest extends MarathonSpec {

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] def portRange(role: String, begin: Long, end: Long): PortRange = {
    PortRange(role, Range.inclusive(begin.toInt, end.toInt))
  }

  private[this] def withRandomSeeds(input: Seq[PortRange], expectedOutput: Iterable[PortWithRole]): Unit = {
    for (seed <- 1 to 10) {
      withClue(s"seed = $seed") {
        val rand = new Random(new util.Random(seed.toLong))

        assert(PortWithRole.lazyRandomPortsFromRanges(rand)(input).to[Set] == expectedOutput.to[Set])
      }
    }

  }

  test("works for empty seq") {
    assert(PortWithRole.lazyRandomPortsFromRanges()(Seq.empty).to[Seq] == Seq.empty)
  }

  test("works for one element range") {
    assert(
      PortWithRole.lazyRandomPortsFromRanges()(Seq(portRange("role", 10, 10))).to[Seq] == Seq(PortWithRole("role", 10)))
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

  test("is lazy with one range") {
    // otherwise this will be really really slow
    val start = System.nanoTime()

    val ports = PortWithRole.lazyRandomPortsFromRanges()(Seq(portRange("role", 1, Integer.MAX_VALUE)))
    ports.take(3)

    val duration = FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    log.info(s"duration = ${duration.toMillis}ms")
    assert(duration.toMillis < 500)
  }

  test("is lazy with multiple ranges") {
    // otherwise this will be really really slow
    val start = System.nanoTime()

    val ports = PortWithRole.lazyRandomPortsFromRanges()(
      (0 to 500).map { _ => portRange("role", 1, Integer.MAX_VALUE) }
    )
    ports.take(3)

    val duration = FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    log.info(s"duration = ${duration.toMillis}ms")
    assert(duration.toMillis < 500)
  }

}
