package mesosphere.marathon.api.v2.json

import mesosphere.marathon.state.{ AppDefinition, Timestamp, PathId, Group }
import org.scalatest.{ GivenWhenThen, Matchers, FunSuite }
import PathId._

class V2GroupUpdateTest extends FunSuite with Matchers with GivenWhenThen {

  test("A group update can be applied to an empty group") {
    Given("An empty group with updates")
    val group = V2Group(Group.empty)
    val update = V2GroupUpdate(PathId.empty, Set.empty[V2AppDefinition], Set(
      V2GroupUpdate("test".toPath, Set.empty[V2AppDefinition], Set(
        V2GroupUpdate.empty("foo".toPath)
      )),
      V2GroupUpdate(
        "apps".toPath,
        Set(V2AppDefinition("app1".toPath, dependencies = Set("d1".toPath, "../foo".toPath, "/test".toPath)))
      )
    )
    )
    val timestamp = Timestamp.now()

    When("The update is performed")
    val result = update(group, timestamp).toGroup()

    Then("The update is applied correctly")
    result.id should be(PathId.empty)
    result.groups should have size 2
    val test = result.group("test".toRootPath)
    test should be('defined)
    test.get.groups should have size 1
    val apps = result.group("apps".toRootPath)
    apps should be('defined)
    apps.get.apps should have size 1
    val app = apps.get.apps.head
    app.id.toString should be ("/apps/app1")
    app.dependencies should be (Set("/apps/app1/d1".toPath, "/apps/foo".toPath, "/test".toPath))
  }

  test("A group update can be applied to existing entries") {
    Given("A group with updates of existing nodes")
    val actual = V2Group(PathId.empty, groups = Set(
      V2Group("/test".toPath, apps = Set(V2AppDefinition("/test/bla".toPath))),
      V2Group("/apps".toPath, groups = Set(V2Group("/apps/foo".toPath)))
    ))
    val update = V2GroupUpdate(
      PathId.empty,
      Set.empty[V2AppDefinition],
      Set(
        V2GroupUpdate(
          "test".toPath,
          Set.empty[V2AppDefinition],
          Set(V2GroupUpdate.empty("foo".toPath))
        ),
        V2GroupUpdate(
          "apps".toPath,
          Set(V2AppDefinition("app1".toPath, dependencies = Set("d1".toPath, "../foo".toPath, "/test".toPath)))
        )
      )
    )
    val timestamp = Timestamp.now()

    When("The update is performed")
    val result: Group = update(actual, timestamp).toGroup()

    Then("The update is applied correctly")
    result.id should be(PathId.empty)
    result.groups should have size 2
    val test = result.group("test".toRootPath)
    test should be('defined)
    test.get.groups should have size 1
    test.get.apps should have size 1
    val apps = result.group("apps".toRootPath)
    apps should be('defined)
    apps.get.groups should have size 1
    apps.get.apps should have size 1
    val app = apps.get.apps.head
    app.id.toString should be ("/apps/app1")
    app.dependencies should be (Set("/apps/app1/d1".toPath, "/apps/foo".toPath, "/test".toPath))
  }

  test("V2GroupUpdate will update a Group correctly") {
    Given("An existing group with two subgroups")
    val current = V2Group(
      "/test".toPath,
      groups = Set(
        V2Group("/test/group1".toPath, Set(V2AppDefinition("/test/group1/app1".toPath))),
        V2Group("/test/group2".toPath, Set(V2AppDefinition("/test/group2/app2".toPath)))
      )
    )

    When("A group update is applied")
    val update = V2GroupUpdate(
      "/test".toPath,
      Set.empty[V2AppDefinition],
      Set(
        V2GroupUpdate("/test/group1".toPath, Set(V2AppDefinition("/test/group1/app3".toPath))),
        V2GroupUpdate(
          "/test/group3".toPath,
          Set.empty[V2AppDefinition],
          Set(V2GroupUpdate("/test/group3/sub1".toPath, Set(V2AppDefinition("/test/group3/sub1/app4".toPath))))
        )
      )
    )

    val timestamp = Timestamp.now()
    val result = update(current, timestamp).toGroup()

    Then("The update is reflected in the current group")
    result.id.toString should be("/test")
    result.apps should be('empty)
    result.groups should have size 2
    val group1 = result.group("/test/group1".toPath).get
    val group3 = result.group("/test/group3".toPath).get
    group1.id should be("/test/group1".toPath)
    group1.apps.head.id should be("/test/group1/app3".toPath)
    group3.id should be("/test/group3".toPath)
    group3.apps should be('empty)
  }

  test("A group update should not contain a version") {
    val update = V2GroupUpdate(None, version = Some(Timestamp.now()))
    intercept[IllegalArgumentException] {
      update(V2Group(Group.empty), Timestamp.now())
    }
  }

  test("A group update should not contain a scaleBy") {
    val update = V2GroupUpdate(None, scaleBy = Some(3))
    intercept[IllegalArgumentException] {
      update(V2Group(Group.empty), Timestamp.now())
    }
  }
}
