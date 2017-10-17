package mesosphere.marathon
package tasks

import java.util.concurrent.TimeUnit

import mesosphere.marathon.tasks.PortsMatcher.{ PortRange, PortWithRole }
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
object PortsMatcherBenchmark {

  def portRange(role: String, begin: Long, end: Long): PortRange = {
    PortRange(role, begin.toInt, end.toInt)
  }

  val numberOfRanges = 500
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class PortsMatcherBenchmark {

  import PortsMatcherBenchmark._

  @Benchmark
  def lazyRandomPortsFromRangesSpeed(hole: Blackhole): Unit = {
    val ports = PortWithRole.lazyRandomPortsFromRanges()(Seq(portRange("role", 1, Integer.MAX_VALUE)))
    assert(ports.take(3).toSet.size == 3, "taken ports include duplicates")
    hole.consume(ports)
  }

  @Benchmark
  def lazyRandomPortsFromMultipleRangesSpeed(hole: Blackhole): Unit = {
    val ports = PortWithRole.lazyRandomPortsFromRanges()(
      (0 to numberOfRanges).map { i =>
        val rangeSize: Long = Integer.MAX_VALUE.toLong / numberOfRanges.toLong
        portRange("role", i * rangeSize, (i + 1) * rangeSize - 1)
      }
    )

    assert(ports.take(3).toSet == 3, "taken ports include duplicates")

    hole.consume(ports)
  }
}
