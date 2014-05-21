package mesosphere.marathon.integration

import org.scalatest._
import mesosphere.marathon.api.v2.{ScalingStrategy, Group}

class IntegrationTest extends IntegrationFunSuite with SingleMarathonIntegrationTest with Matchers with BeforeAndAfter {

  //clean up state before running the test case
  before(marathon.cleanUp())

  test("create empty group successfully") {
    val group = Group.empty().copy(id="test")
    val result = marathon.createGroup(group)
    result.code should be(204) //no content
    val event = waitForEvent("group_change_success")
    event.info("groupId") should be(group.id)
  }

  test("update empty group successfully") {
    val group = Group.empty().copy(id="test2")
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    marathon.updateGroup(group.copy(scalingStrategy=ScalingStrategy(1)))
    waitForEvent("group_change_success")
    val result = marathon.group("test2")
    result.code should be(200)
    result.value.scalingStrategy should be(ScalingStrategy(1))
  }
}
