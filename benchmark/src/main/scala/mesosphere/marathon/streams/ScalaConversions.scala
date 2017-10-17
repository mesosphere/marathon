package mesosphere.marathon
package streams

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.collection.convert.DecorateAsJava

@State(Scope.Benchmark)
object ScalaConversions {
  val small: Seq[Int] = 0.to(100)
  val medium: Seq[Int] = 0.to(1000)
  val large: Seq[Int] = 0.to(10000)
  val veryLarge: Seq[Int] = 0.to(1000 * 1000)
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class ScalaConversions extends DecorateAsJava {
  import ScalaConversions._

  @Benchmark
  def smallAsJavaStream(hole: Blackhole): Unit = {
    hole.consume(small.asJava)
  }

  @Benchmark
  def smallConverted(hole: Blackhole): Unit = {
    hole.consume(small.asJava)
  }

  @Benchmark
  def mediumAsJavaStream(hole: Blackhole): Unit = {
    hole.consume(medium.asJava)
  }

  @Benchmark
  def mediumConverted(hole: Blackhole): Unit = {
    hole.consume(medium.asJava)
  }

  @Benchmark
  def largeAsJavaStream(hole: Blackhole): Unit = {
    hole.consume(large.asJava)
  }

  @Benchmark
  def largeConverted(hole: Blackhole): Unit = {
    hole.consume(large.asJava)
  }

  @Benchmark
  def veryLargeAsJavaStream(hole: Blackhole): Unit = {
    hole.consume(veryLarge.asJava)
  }

  @Benchmark
  def veryLargeConverted(hole: Blackhole): Unit = {
    hole.consume(veryLarge.asJava)
  }
}
