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
    PortRange(role, begin.toInt, end.toInt)
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

  // benchmark duration to allow calculate relative acceptable times
  private[this] lazy val benchmarkDuration = {
    System.gc()
    val start = System.nanoTime()
    assert((1 to 100000).map(_.toLong).sum == 5000050000L)
    FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
  }

  test("is lazy with one range") {
    // if it is not implemented lazily, this will be really really slow

    def performTest(): Unit = {
      val ports = PortWithRole.lazyRandomPortsFromRanges()(Seq(portRange("role", 1, Integer.MAX_VALUE)))
      assert(ports.take(3).toSet.size == 3)
    }

    // warm up
    (1 to 100).foreach(_ => performTest())

    // real test
    System.gc()
    val start = System.nanoTime()
    performTest()
    val duration = FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)

    val allowTimeForTest = benchmarkDuration
    log.info(
      s"benchmarkDuration = ${benchmarkDuration.toMillis}ms, " +
        s"allowing time for test = ${allowTimeForTest.toMillis}ms"
    )
    log.info(s"duration = ${duration.toMillis}ms")
    assert(duration.toMillis < allowTimeForTest.toMillis)
  }

  test("is lazy with multiple ranges") {
    // if it is not implemented lazily, this will be really really slow

    val numberOfRanges = 500

    def performTest(seed: Int): Unit = {
      val rand = new Random(new util.Random(seed.toLong))
      val ports = PortWithRole.lazyRandomPortsFromRanges(rand)(
        (0 to numberOfRanges).map { i =>
          val rangeSize: Long = Integer.MAX_VALUE.toLong / numberOfRanges.toLong
          portRange("role", i * rangeSize, (i + 1) * rangeSize - 1)
        }
      )

      val takenPorts = ports.take(3).toSet
      assert(takenPorts.size == 3, s"unexpected taken ports: $takenPorts")
    }

    // warm up
    for (seed <- 1 to 100) performTest(seed)

    // real test
    val iterations = 100
    System.gc()
    val start = System.nanoTime()
    for (seed <- 1 to iterations) performTest(seed)
    val duration = FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)

    val allowTimeForTest = benchmarkDuration * 50
    log.info(
      s"benchmarkDuration = ${benchmarkDuration.toMillis}ms, " +
        s"allowing time for test = ${allowTimeForTest.toMillis}ms"
    )
    log.info(s"duration = ${duration.toMillis}ms for $iterations iterations and $numberOfRanges ranges")
    assert(duration.toMillis < allowTimeForTest.toMillis)
  }
}
