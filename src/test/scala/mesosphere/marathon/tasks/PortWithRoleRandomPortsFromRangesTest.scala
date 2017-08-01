package mesosphere.marathon
package tasks

import java.util
import java.util.concurrent.TimeUnit

import mesosphere.UnitTest
import mesosphere.marathon.tasks.PortsMatcher.{ PortRange, PortWithRole }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
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

    // benchmark duration to allow calculate relative acceptable times
    // This really should be in the benchmark project.
    lazy val benchmarkDuration = {
      System.gc()
      val start = System.nanoTime()
      assert((1 to 100000).map(_.toLong).sum == 5000050000L)
      FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    }

    "is lazy with one range" in {
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
      duration.toMillis should be < allowTimeForTest.toMillis withClue "Port range benchmark failed. It does not seem to be lazy."
    }

    "is lazy with multiple ranges" in {
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
      duration.toMillis should be < allowTimeForTest.toMillis withClue "Port range benchmark failed. It does not seem to be lazy."
    }
  }
}
