package mesosphere.marathon.api.v2

import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import mesosphere.marathon.state.{ PathId, Timestamp, ScalingStrategy, Group }
import mesosphere.marathon.api.v1.AppDefinition
import PathId._

class GroupsResourceTest extends FunSuite with GivenWhenThen with Matchers {

  ignore("A GroupResource can validate GroupUpdates") {
    //TODO: write me
  }

  test("GroupUpdate will update a Group correctly") {
    Given("An existing group with two subgroups")
    val scaling = ScalingStrategy(0.5, Some(1))
    val current = Group("/test".toPath, scaling, groups = Set(
      Group("/test/group1".toPath, scaling, Set(AppDefinition("/test/group1/app1".toPath))),
      Group("/test/group2".toPath, scaling, Set(AppDefinition("/test/group2/app2".toPath)))
    ))

    When("A group update is applied")
    val update = GroupUpdate("/test", scaling, Set.empty[AppDefinition], Set(
      GroupUpdate("/test/group1", scaling, Set(AppDefinition("/test/group1/app3".toPath))),
      GroupUpdate("/test/group3", scaling, Set.empty[AppDefinition], Set(
        GroupUpdate("/test/group3/sub1", scaling, Set(AppDefinition("/test/group3/sub1/app4".toPath)))
      ))
    ))

    val timestamp = Timestamp.now()
    val group = update(current, timestamp)

    Then("The update is reflected in the current group")
    group.scalingStrategy should be(scaling)
    group.id.toString should be("/test")
    group.apps should be('empty)
    group.groups should have size 2
    val group1 = group.group("/test/group1".toPath).get
    val group3 = group.group("/test/group3".toPath).get
    group1.id should be("/test/group1".toPath)
    group1.apps.head.id should be("/test/group1/app3".toPath)
    group3.id should be("/test/group3".toPath)
    group3.apps should be('empty)
  }
}

