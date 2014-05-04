package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec

class TimestampTest extends MarathonSpec {

  test("Ordering") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t1.compare(t2) < 0)
  }
}
