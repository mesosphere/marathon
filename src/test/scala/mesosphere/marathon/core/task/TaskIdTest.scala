package mesosphere.marathon
package core.task

import mesosphere.UnitTest
import mesosphere.marathon.core.task.Task.TaskIdWithIncarnation
import org.scalatest.prop.TableDrivenPropertyChecks

class TaskIdTest extends UnitTest with TableDrivenPropertyChecks {

  "TaskId.apply" should {

    val ids = Table(
      ("id string"),
      ("$tiwi$.myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon.1"),
      ("$tiwi$.myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.$anon.3"),
      ("$tiwi$.myGroup_myApp.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails.2"),
      ("$tiwi$.myGroup_myApp.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.rails.42")
    )
    forAll(ids) { id =>
      s"parse id $id to expected type" in {
        val taskId = Task.Id(id)

        taskId shouldBe a[TaskIdWithIncarnation]
        taskId.idString should be(id)
      }
    }
  }
}
