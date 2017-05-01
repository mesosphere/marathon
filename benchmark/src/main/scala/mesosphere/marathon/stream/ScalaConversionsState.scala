package mesosphere.marathon
package stream

import org.openjdk.jmh.annotations.{ Scope, State }

import scala.collection.immutable.Seq

@State(Scope.Benchmark)
object ScalaConversionsState {
  val small: Seq[Int] = 0.to(100)
  val medium: Seq[Int] = 0.to(1000)
  val large: Seq[Int] = 0.to(10000)
  val veryLarge: Seq[Int] = 0.to(1000 * 1000)
}
