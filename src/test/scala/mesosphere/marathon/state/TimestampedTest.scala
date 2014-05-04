package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec

class TimestampedTest extends MarathonSpec {

  class A(val version: Timestamp = Timestamp.now) extends Timestamped

  test("Ordering") {
    val a1 = new A(Timestamp(1393989019980L))
    val a2 = new A(Timestamp(1393989019981L))
    val a3 = new A(Timestamp(1393989019982L))

    implicit val orderingOnA = Timestamped.timestampOrdering[A]

    assert(Seq(a2, a3, a1).sorted == Seq(a1, a2, a3))
  }
}
