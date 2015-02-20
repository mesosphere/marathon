package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import org.joda.time.{ DateTime, DateTimeZone }

class TimestampTest extends MarathonSpec {

  test("Ordering") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t1.compare(t2) < 0)
  }

  test("Independent of timezone") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(new DateTime(1024).toDateTime(DateTimeZone.forOffsetHours(2)))

    assert(t1 == t2)
    assert(t1.hashCode == t2.hashCode)
  }
}
