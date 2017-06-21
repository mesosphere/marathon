package mesosphere.marathon
package streams

import java.util
import java.util.concurrent.TimeUnit

import mesosphere.marathon.stream.Implicits._
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.collection.JavaConverters._

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
class ScalaConversions {
  import ScalaConversions._

  @Benchmark
  def smallAsJavaStream(hole: Blackhole): Unit = {
    val asJava: util.List[Int] = small
    hole.consume(asJava)
  }

  @Benchmark
  def smallConverted(hole: Blackhole): Unit = {
    val asJava = small.asJava
    hole.consume(asJava)
  }

  @Benchmark
  def mediumAsJavaStream(hole: Blackhole): Unit = {
    val asJava: util.List[Int] = medium
    hole.consume(asJava)
  }

  @Benchmark
  def mediumConverted(hole: Blackhole): Unit = {
    val asJava = medium.asJava
    hole.consume(asJava)
  }

  @Benchmark
  def largeAsJavaStream(hole: Blackhole): Unit = {
    val asJava: util.List[Int] = large
    hole.consume(asJava)
  }

  @Benchmark
  def largeConverted(hole: Blackhole): Unit = {
    val asJava = large.asJava
    hole.consume(asJava)
  }

  @Benchmark
  def veryLargeAsJavaStream(hole: Blackhole): Unit = {
    val asJava: util.List[Int] = veryLarge
    hole.consume(asJava)
  }

  @Benchmark
  def veryLargeConverted(hole: Blackhole): Unit = {
    val asJava = veryLarge.asJava
    hole.consume(asJava)
  }
}
