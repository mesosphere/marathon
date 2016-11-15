package mesosphere.marathon
package state

import mesosphere.marathon.test.MarathonSpec
import org.joda.time.{ DateTime, DateTimeZone }

class TimestampTest extends MarathonSpec {

  test("Ordering") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t1.compare(t2) < 0)
  }

  test("Before") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t1.before(t2))
  }

  test("After") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t2.after(t1))
  }

  test("Older") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t1.olderThan(t2))
  }

  test("Younger") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assert(t2.youngerThan(t1))
  }

  test("Independent of timezone") {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(new DateTime(1024).toDateTime(DateTimeZone.forOffsetHours(2))) // linter:ignore TypeToType

    assert(t1 == t2)
    assert(t1.hashCode == t2.hashCode)
  }
}
