package mesosphere.mesos.protos

import mesosphere.UnitTest
import org.apache.mesos.Protos

import scala.collection.immutable.Seq

class ImplicitsSpec extends UnitTest {

  import Implicits._

  "Implicits" should {
    "convert ExecutorID to proto and back" in {
      val caseClass = ExecutorID("klaus")
      assert(caseClass == ((caseClass: Protos.ExecutorID): ExecutorID))
    }

    "convert FrameworkID to proto and back" in {
      val caseClass = FrameworkID("botnet")
      assert(caseClass == ((caseClass: Protos.FrameworkID): FrameworkID))
    }

    "convert FrameworkInfo to proto and back" in {
      val caseClass = FrameworkInfo("botnet")
      assert(caseClass == ((caseClass: Protos.FrameworkInfo): FrameworkInfo))
    }

    "convert Range to proto and back" in {
      val caseClass = Range(23, 42)
      assert(caseClass == ((caseClass: Protos.Value.Range): Range))
    }

    "convert RangesResource to proto and back" in {
      val caseClass = RangesResource("ports", Seq(Range(1, 5), Range(6, 9)))
      assert(caseClass == ((caseClass: Protos.Resource): Resource))
    }

    "convert ScalarResource to proto and back" in {
      val caseClass = ScalarResource("cpus", 4.0)
      assert(caseClass == ((caseClass: Protos.Resource): Resource))
    }

    "convert SetResource to proto and back" in {
      val caseClass = SetResource("ips", Set("1.1.1.1", "2.2.2.2"))
      assert(caseClass == ((caseClass: Protos.Resource): Resource))
    }

    "convert SlaveID to proto and back" in {
      val caseClass = SlaveID("foo")
      assert(caseClass == ((caseClass: Protos.SlaveID): SlaveID))
    }

    "convert TaskID to proto and back" in {
      val caseClass = TaskID("foo")
      assert(caseClass == ((caseClass: Protos.TaskID): TaskID))
    }

    "convert TaskStatus to proto and back" in {
      val one = TaskStatus(TaskID("1"), TaskRunning)
      val two = (one: Protos.TaskStatus): TaskStatus
      assert(one.taskId == two.taskId)
      assert(one.state == two.state)
      assert(one.message == two.message)
      // Can't test byte array for equality
      assert(one.data.length == two.data.length)
      assert(one.slaveId == two.slaveId)
      assert(one.timestamp == two.timestamp)
    }

    "convert TextAttribute to proto and back" in {
      val caseClass = TextAttribute("rack", "1")
      assert(caseClass == ((caseClass: Protos.Attribute): Attribute))
    }

    "convert Offer to proto and back" in {
      val caseClass = Offer(
        OfferID("offer-1"),
        FrameworkID("marathon"),
        SlaveID("slave-1"),
        "host1.example.com",
        Seq(ScalarResource("cpus", 4), ScalarResource("mem", 4096)),
        Seq(TextAttribute("region", "us-west-2")),
        Seq(ExecutorID("exe-1"))
      )
      assert(caseClass == ((caseClass: Protos.Offer): Offer))
    }

    "convert OfferID to proto and back" in {
      val caseClass = OfferID("123-5050-323")
      assert(caseClass == ((caseClass: Protos.OfferID): OfferID))
    }
  }
}
