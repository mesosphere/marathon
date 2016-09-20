package mesosphere.marahon.benchmarks.streams

import java.util

import mesosphere.marathon.stream._
import org.openjdk.jmh.annotations.{ Benchmark, Scope, State }

import scala.collection.immutable.Seq

class ScalaConversions {
  @State(Scope.Benchmark)
  val small: Seq[Int] = 0.to(100)
  @State(Scope.Benchmark)
  val medium: Seq[Int] = 0.to(1000)
  @State(Scope.Benchmark)
  val big: Seq[Int] = 0.to(100000)

  @Benchmark
  def smallAsJavaStream(): Unit = {
    val asJava: util.List[Int] = small
  }

  @Benchmark
  def smallConverted(): Unit = {
    val asJava = small.asJava
  }

}
