package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit

import mesosphere.marathon.state.PathId
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@State(Scope.Benchmark)
object PathIdBenchmark {

  @Param(value = Array("2", "10", "100", "1000"))
  var numberOfPaths: Int = _
  lazy val pathIdSegments = (1 to numberOfPaths)

  lazy val pathId = PathId(pathIdSegments.map(_ => Random.alphanumeric.filter(_.isLetter).take(10).mkString).toList)

}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
class PathIdBenchmark {
  import PathIdBenchmark._

  @Benchmark
  def allParentsSpeed(hole: Blackhole): Unit = {
    val parents = pathId.allParents
    hole.consume(parents)
  }
}
