package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit

import mesosphere.marathon.state.PathId
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@State(Scope.Benchmark)
object PathIdBenchmark {

  lazy val pathIdSegments = (1 to 100)

  lazy val pathId = PathId(pathIdSegments.map(_ => Random.alphanumeric.filter(_.isLetter).take(10).mkString).toList)

}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class PathIdBenchmark {
  import PathIdBenchmark._

  @Benchmark
  def allParentsSpeed(hole: Blackhole): Unit = {
    val parents = pathId.allParents
    hole.consume(parents)
  }
}
