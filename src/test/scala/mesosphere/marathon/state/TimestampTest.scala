package mesosphere.marathon
package state

import java.time.format.DateTimeFormatter
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit

import mesosphere.UnitTest

import scala.concurrent.duration._

class TimestampTest extends UnitTest {

  "Timestamp" when {
    "ordering" should {
      "have the correct order" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t1.compare(t2) should be < 0
      }
      "be before" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t1.before(t2) shouldBe true
      }
      "be after" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t2.after(t1) shouldBe true
      }
      "be older" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t1.olderThan(t2) shouldBe true
      }
      "be younger" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t2.youngerThan(t1) shouldBe true
      }
      "be expired" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(2048)
        t1.expired(t2, by = 100.minutes) shouldBe false
        t1.expired(t2, by = 10.millis) shouldBe true
      }
      "independent of timezone" in {
        val t1 = Timestamp(1024)
        val t2 = Timestamp(OffsetDateTime.ofInstant(Instant.ofEpochMilli(1024), ZoneOffset.ofHours(2))) // linter:ignore TypeToType

        (t1 == t2) shouldBe true
        (t1.hashCode == t2.hashCode) shouldBe true
      }
      "fail for incorrect string" in {
        intercept[IllegalArgumentException] {
          Timestamp("20:39:32.972Z")
        }
      }
    }
    "converting from Mesos" should {
      import org.apache.mesos
      "resolve TaskStatus.timestamp correctly" in {
        val instant = Instant.now()
        val taskStatus = mesos.Protos.TaskStatus.newBuilder()
          .setTimestamp(instant.getEpochSecond.toDouble)
          .setTaskId(mesos.Protos.TaskID.newBuilder().setValue("task-1").build())
          .setState(mesos.Protos.TaskState.TASK_STAGING)
          .build()
        val timestamp = Timestamp.fromTaskStatus(taskStatus)

        // We loose precision, therefore need to truncate the instant to seconds
        timestamp.millis shouldEqual instant.truncatedTo(ChronoUnit.SECONDS).toEpochMilli
      }
    }
    "converting to/from OffsetDateTime" should {
      "be compatible" in {

        val dateTimeStr = "2018-09-26T12:46:13.587Z"

        val timestamp = Timestamp(dateTimeStr)
        val timestampString = timestamp.toString

        val offsetDateTime = OffsetDateTime.parse(timestampString)

        val formatted = Timestamp.WriteFormatter.format(offsetDateTime)

        Timestamp(offsetDateTime) shouldEqual timestamp
        Timestamp(timestampString) shouldEqual timestamp
        Timestamp(formatted) shouldEqual timestamp

        OffsetDateTime.parse(dateTimeStr, Timestamp.ReadFormatter).format(Timestamp.WriteFormatter) shouldEqual timestamp.toString
      }
    }
  }
}
