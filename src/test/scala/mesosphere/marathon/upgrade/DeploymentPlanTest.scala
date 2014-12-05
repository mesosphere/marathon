package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class DeploymentPlanTest extends MarathonSpec with Matchers with GivenWhenThen {

  protected def actionsOf(plan: DeploymentPlan): Seq[DeploymentAction] =
    plan.steps.flatMap(_.actions)

  test("partition a simple group's apps into concurrently deployable subsets") {
    Given("a group of four apps with some simple dependencies")
    val aId = "/test/database/a".toPath
    val bId = "/test/service/b".toPath
    val cId = "/c".toPath
    val dId = "/d".toPath

    val a = AppDefinition(aId, version = Timestamp(0))
    val b = AppDefinition(bId, dependencies = Set(aId), version = Timestamp(0))
    val c = AppDefinition(cId, dependencies = Set(aId), version = Timestamp(0))
    val d = AppDefinition(dId, dependencies = Set(bId), version = Timestamp(0))

    val group = Group(
      id = "/test".toPath,
      apps = Set(c, d),
      groups = Set(
        Group("/test/database".toPath, Set(a)),
        Group("/test/service".toPath, Set(b))
      )
    )

    When("the group's apps are grouped by the longest outbound path")
    val partitionedApps = DeploymentPlan.appsGroupedByLongestPath(group)

    Then("three equivalence classes should be computed")
    partitionedApps should have size (3)

    partitionedApps.keySet should contain (1)
    partitionedApps.keySet should contain (2)
    partitionedApps.keySet should contain (3)

    partitionedApps(2) should have size (2)
  }

  test("partition a complex group's apps into concurrently deployable subsets") {
    Given("a group of four apps with some simple dependencies")
    val aId = "/a".toPath
    val bId = "/b".toPath
    val cId = "/c".toPath
    val dId = "/d".toPath
    val eId = "/e".toPath
    val fId = "/f".toPath

    val a = AppDefinition(aId, dependencies = Set(bId, cId), version = Timestamp(0))
    val b = AppDefinition(bId, dependencies = Set(cId), version = Timestamp(0))
    val c = AppDefinition(cId, dependencies = Set(dId), version = Timestamp(0))
    val d = AppDefinition(dId, version = Timestamp(0))
    val e = AppDefinition(eId, version = Timestamp(0))

    val group = Group(
      id = "/".toPath,
      apps = Set(a, b, c, d, e)
    )

    When("the group's apps are grouped by the longest outbound path")
    val partitionedApps = DeploymentPlan.appsGroupedByLongestPath(group)

    Then("three equivalence classes should be computed")
    partitionedApps should have size (4)

    partitionedApps.keySet should contain (1)
    partitionedApps.keySet should contain (2)
    partitionedApps.keySet should contain (3)
    partitionedApps.keySet should contain (4)

    partitionedApps(1) should have size (2)
  }

  test("start from empty group") {
    val app = AppDefinition("/app".toPath, instances = 2)
    val from = Group("/group".toPath, Set.empty)
    val to = Group("/group".toPath, Set(app))
    val plan = DeploymentPlan(from, to)

    actionsOf(plan) should contain (StartApplication(app, 0))
    actionsOf(plan) should contain (ScaleApplication(app, app.instances))
  }

  test("start from running group") {
    val apps = Set(AppDefinition("/app".toPath, Some("sleep 10")), AppDefinition("/app2".toPath, Some("cmd2")), AppDefinition("/app3".toPath, Some("cmd3")))
    val update = Set(AppDefinition("/app".toPath, Some("sleep 30")), AppDefinition("/app2".toPath, Some("cmd2"), instances = 10), AppDefinition("/app4".toPath, Some("cmd4")))

    val from = Group("/group".toPath, apps)
    val to = Group("/group".toPath, update)
    val plan = DeploymentPlan(from, to)

    /*
    plan.toStart should have size 1
    plan.toRestart should have size 1
    plan.toScale should have size 1
    plan.toStop should have size 1
    */
  }

  test("can compute affected app ids") {
    val apps = Set(AppDefinition("/app".toPath, Some("sleep 10")), AppDefinition("/app2".toPath, Some("cmd2")), AppDefinition("/app3".toPath, Some("cmd3")))
    val update = Set(AppDefinition("/app".toPath, Some("sleep 30")), AppDefinition("/app2".toPath, Some("cmd2"), instances = 10), AppDefinition("/app4".toPath, Some("cmd4")))

    val from = Group("/group".toPath, apps)
    val to = Group("/group".toPath, update)
    val plan = DeploymentPlan(from, to)

    plan.affectedApplicationIds should equal (Set("/app".toPath, "/app2".toPath, "/app3".toPath, "/app4".toPath))
    plan.isAffectedBy(plan) should equal (right = true)
    plan.isAffectedBy(DeploymentPlan(from, from)) should equal (right = false)
  }

  test("when updating a group with dependencies, the correct order is computed") {

    Given("Two application updates with command and scale changes")
    val mongoId = "/test/database/mongo".toPath
    val serviceId = "/test/service/srv1".toPath
    val strategy = UpgradeStrategy(0.75)

    val mongo: (AppDefinition, AppDefinition) =
      AppDefinition(mongoId, Some("mng1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy)

    val service: (AppDefinition, AppDefinition) =
      AppDefinition(serviceId, Some("srv1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(serviceId, Some("srv2"), dependencies = Set(mongoId), instances = 10, upgradeStrategy = strategy)

    val from = Group(
      id = "/test".toPath,
      groups = Set(
        Group("/test/database".toPath, Set(mongo._1)),
        Group("/test/service".toPath, Set(service._1))
      )
    )

    val to = Group("/test".toPath, groups = Set(
      Group("/test/database".toPath, Set(mongo._2)),
      Group("/test/service".toPath, Set(service._2))
    ))

    When("the deployment plan is computed")
    val plan = DeploymentPlan(from, to)

    Then("the deployment steps are correct")
    plan.steps should have size 2
    plan.steps(0).actions.toSet should equal (Set(RestartApplication(mongo._2, 3, 8)))
    plan.steps(1).actions.toSet should equal (Set(RestartApplication(service._2, 3, 10)))
  }

  test("when updating a group without dependencies, a random order of updates is used") {
    Given("Two application updates with command and scale changes")
    val mongoId = "/test/database/mongo".toPath
    val serviceId = "/test/service/srv1".toPath
    val strategy = UpgradeStrategy(0.75)

    val mongo =
      AppDefinition(mongoId, Some("mng1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy)

    val service =
      AppDefinition(serviceId, Some("srv1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(serviceId, Some("srv2"), instances = 10, upgradeStrategy = strategy)

    val from: Group = Group("/test".toPath, groups = Set(
      Group("/test/database".toPath, Set(mongo._1)),
      Group("/test/service".toPath, Set(service._1))
    ))

    val to: Group = Group("/test".toPath, groups = Set(
      Group("/test/database".toPath, Set(mongo._2)),
      Group("/test/service".toPath, Set(service._2))
    ))

    When("the deployment plan is computed")
    val plan = DeploymentPlan(from, to)

    Then("the deployment steps are correct")
    plan.steps should have size 2
    plan.steps(0).actions.toSet should equal (Set(RestartApplication(mongo._2, 3, 8)))
    plan.steps(1).actions.toSet should equal (Set(RestartApplication(service._2, 3, 10)))
  }

  test("when updating a group with dependent and independent applications, the correct order is computed") {
    Given("application updates with command and scale changes")
    val mongoId = "/test/database/mongo".toPath
    val serviceId = "/test/service/srv1".toPath
    val appId = "/test/independent/app".toPath
    val strategy = UpgradeStrategy(0.75)

    val mongo =
      AppDefinition(mongoId, Some("mng1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy)

    val service =
      AppDefinition(serviceId, Some("srv1"), instances = 4, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(serviceId, Some("srv2"), dependencies = Set(mongoId), instances = 10, upgradeStrategy = strategy)

    val independent =
      AppDefinition(appId, Some("app1"), instances = 1, version = Timestamp(0), upgradeStrategy = strategy) ->
        AppDefinition(appId, Some("app2"), instances = 3, upgradeStrategy = strategy)

    val toStop = AppDefinition("/test/service/toStop".toPath, instances = 1, dependencies = Set(mongoId))
    val toStart = AppDefinition("/test/service/toStart".toPath, instances = 2, dependencies = Set(serviceId))

    val from: Group = Group("/test".toPath, groups = Set(
      Group("/test/database".toPath, Set(mongo._1)),
      Group("/test/service".toPath, Set(service._1, toStop)),
      Group("/test/independent".toPath, Set(independent._1))
    ))

    val to: Group = Group("/test".toPath, groups = Set(
      Group("/test/database".toPath, Set(mongo._2)),
      Group("/test/service".toPath, Set(service._2, toStart)),
      Group("/test/independent".toPath, Set(independent._2))
    ))

    When("the deployment plan is computed")
    val plan = DeploymentPlan(from, to)

    Then("the deployment contains steps for dependent and independent applications")
    plan.steps should have size (6)

    actionsOf(plan) should have size (6)

    plan.steps(0).actions.toSet should equal (Set(StopApplication(toStop)))
    plan.steps(1).actions.toSet should equal (Set(StartApplication(toStart, 0)))
    plan.steps(2).actions.toSet should equal (Set(RestartApplication(mongo._2, 3, 8)))
    plan.steps(3).actions.toSet should equal (Set(RestartApplication(independent._2, 1, 3)))
    plan.steps(4).actions.toSet should equal (Set(RestartApplication(service._2, 3, 10)))
    plan.steps(5).actions.toSet should equal (Set(ScaleApplication(toStart, 2)))
  }

  test("when the only action is to stop an application") {
    Given("application updates with only the removal of an app")
    val strategy = UpgradeStrategy(0.75)
    val app = AppDefinition("/test/independent/app".toPath, Some("app2"), instances = 3, upgradeStrategy = strategy) -> None
    val from: Group = Group("/test".toPath, groups = Set(
      Group("/test/independent".toPath, Set(app._1))
    ))
    val to: Group = Group("/test".toPath)

    When("the deployment plan is computed")
    val plan = DeploymentPlan(from, to)

    Then("the deployment contains one step consisting of one stop action")
    plan.steps should have size 1
    plan.steps(0).actions.toSet should be(Set(StopApplication(app._1)))
  }

  // regression test for #765
  test("Should create non-empty deployment plan when only args have changed") {
    val app = AppDefinition(id = "/test".toPath, cmd = Some("sleep 5"))
    val appNew = app.copy(args = Some(Seq("foo")))

    val from = Group("/".toPath, apps = Set(app))
    val to = from.copy(apps = Set(appNew))

    val plan = DeploymentPlan(from, to)

    plan.steps should not be empty
  }
}
