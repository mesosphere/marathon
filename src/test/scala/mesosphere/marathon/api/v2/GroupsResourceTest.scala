package mesosphere.marathon.api.v2

import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import mesosphere.marathon.state.{ Timestamp, ScalingStrategy, Group }
import mesosphere.marathon.api.v1.AppDefinition

class GroupsResourceTest extends FunSuite with GivenWhenThen with Matchers {

  ignore("A GroupResource can validate GroupUpdates") {

  }

  test("GroupUpdate will update a Group correctly") {
    Given("An existing group with two subgroups")
    val scaling = ScalingStrategy(0.5, Some(1))
    val current = Group("test", scaling, groups = Seq(
      Group("test/group1", scaling, Seq(AppDefinition("app1"))),
      Group("test/group2", scaling, Seq(AppDefinition("app2")))
    ))

    When("A group update is applied")
    val update = GroupUpdate(None, scaling, groups = Seq(
      GroupUpdate(Some("test/group1"), scaling, Seq(AppDefinition("app3"))),
      GroupUpdate(Some("test/group3"), scaling, groups = Seq(
        GroupUpdate(Some("test/group3/sub1"), scaling, Seq(AppDefinition("app4")))
      ))
    ))
    val timestamp = Timestamp.now()
    val group = update(current, timestamp)

    Then("The update is reflected in the current group")
    group.scalingStrategy should be(scaling)
    group.id.toString should be("test")
    group.apps should be('empty)
    group.groups should have size 2
    group.groups(0).id.toString should be("test/group1")
    group.groups(0).apps(0).id should be("app3")
    group.groups(1).id.toString should be("test/group3")
    group.groups(1).apps should be('empty)
    group.groups(1).groups(0).apps(0).id should be("app4")
  }

}
