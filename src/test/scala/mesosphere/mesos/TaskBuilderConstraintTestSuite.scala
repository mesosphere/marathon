package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.{ MarathonTestHelper, Protos }
import mesosphere.marathon.state._
import mesosphere.marathon.state.AppDefinition.VersionInfo.OnlyVersion
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderConstraintTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._
  import MarathonTestHelper.Implicits._

  "TaskBuilder" when {

    "given an offer and an app defintion with constraints to a rack" should {
      val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000)
        .addAttributes(TextAttribute("rackid", "1"))
        .build

      val app = MarathonTestHelper.makeBasicApp().copy(
        constraints = Set(
          Protos.Constraint.newBuilder
            .setField("rackid")
            .setOperator(Protos.Constraint.Operator.UNIQUE)
            .build()
        )
      )

      val t1 =
        MarathonTestHelper
          .stagedTask(taskId = app.id.toString)
          .withAgentInfo(_.copy(attributes = Iterable(TextAttribute("rackid", "2"))))
          .withHostPorts(Seq(999))
      val t2 =
        MarathonTestHelper
          .stagedTask(taskId = app.id.toString)
          .withAgentInfo(_.copy(attributes = Iterable(TextAttribute("rackid", "3"))))
          .withHostPorts(Seq(999))
      val tasks = Set(t1, t2)

      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())
      val task = builder.buildIfMatches(offer, tasks)
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "return a defined task" in { task should be('defined) }

      // TODO(jeschkies): Do these numbers make sense?
      // TODO(jeschkies): What else shall we test?
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }

    // Test the case where we want tasks to be balanced across racks/AZs
    // and run only one per machine
    "given an offer and an app definition with constraint to a rack and host" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        versionInfo = OnlyVersion(Timestamp(10)),
        constraints = Set(
          Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )

      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("rackid", "1"))
        .build
      val task = builder.buildIfMatches(offer, Seq.empty)

      "define a task" in { task should be('defined) }
    }

    "given an offer from a rack with one task running" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        versionInfo = OnlyVersion(Timestamp(10)),
        constraints = Set(
          Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )
      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offerRack1HostA = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("rackid", "1"))
        .build
      val (runningTaskInfo, _) = builder.buildIfMatches(offerRack1HostA, Set.empty).get
      val runningTasks = Set(MarathonTestHelper.makeTaskFromTaskInfo(runningTaskInfo, offerRack1HostA))

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("beta")
        .addAttributes(TextAttribute("rackid", "1"))
        .build
      val task = builder.buildIfMatches(offer, runningTasks)

      "not take offer and not define the task" in { task should not be ('defined) }

    }

    "given an offer from rack 2 and tasks running on rack 1" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        versionInfo = OnlyVersion(Timestamp(10)),
        constraints = Set(
          Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )
      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offerRack1HostA = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("rackid", "1"))
        .build
      val (runningTaskInfo, _) = builder.buildIfMatches(offerRack1HostA, Set.empty).get
      val runningTasks = Set(MarathonTestHelper.makeTaskFromTaskInfo(runningTaskInfo, offerRack1HostA))

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("gamma")
        .addAttributes(TextAttribute("rackid", "2"))
        .build
      val task = builder.buildIfMatches(offer, runningTasks)

      "define a task" in { task should be('defined) }
    }

    // Nothing prevents having two hosts with the same name in different racks
    "given an offer from rack 2 with same host 'alpha' and tasks running on host 'alpha' on rack 1" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        versionInfo = OnlyVersion(Timestamp(10)),
        constraints = Set(
          Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )
      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offerRack1HostA = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("rackid", "1"))
        .build
      val (runningTaskInfo, _) = builder.buildIfMatches(offerRack1HostA, Set.empty).get
      val runningTasks = Set(MarathonTestHelper.makeTaskFromTaskInfo(runningTaskInfo, offerRack1HostA))

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("rackid", "3"))
        .build
      val task = builder.buildIfMatches(offer, runningTasks)

      "not take offer and not define a task" in { task should not be ('defined) }
    }

    "given an offer with 'spark:disabled' and an app with constraint fields" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        constraints = Set(
          Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )

      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("alpha")
        .addAttributes(TextAttribute("spark", "disabled"))
        .build
      val task = builder.buildIfMatches(offer, Set.empty)

      "not define task" in { task should not be ('defined) }
    }

    "given an offer with 'spark:enabled' and an app with constraint fields" should {
      val app = MarathonTestHelper.makeBasicApp().copy(
        instances = 10,
        constraints = Set(
          Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
          Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
        )
      )

      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())

      val offer = MarathonTestHelper.makeBasicOffer()
        .setHostname("beta")
        .addAttributes(TextAttribute("spark", "enabled"))
        .build
      val task = builder.buildIfMatches(offer, Set.empty)

      "define task" in { task should be('defined) }
    }
  }
}
