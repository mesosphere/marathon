package mesosphere.marathon.upgrade

import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.AppDefinition.VersionInfo.FullVersionInfo
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon._
import mesosphere.marathon.test.Mockito
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.{ GivenWhenThen, Matchers }
import com.wix.accord._

import scala.collection.immutable.Seq

class DeploymentPlanTest extends MarathonSpec with Matchers with GivenWhenThen with Mockito {

  protected def actionsOf(plan: DeploymentPlan): Seq[DeploymentAction] =
    plan.steps.flatMap(_.actions)

  test("partition a simple group's apps into concurrently deployable subsets") {
    Given("a group of four apps with some simple dependencies")
    val aId = "/test/database/a".toPath
    val bId = "/test/service/b".toPath
    val cId = "/c".toPath
    val dId = "/d".toPath

    val a = AppDefinition(aId)
    val b = AppDefinition(bId, dependencies = Set(aId))
    val c = AppDefinition(cId, dependencies = Set(aId))
    val d = AppDefinition(dId, dependencies = Set(bId))

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

    val a = AppDefinition(aId, dependencies = Set(bId, cId))
    val b = AppDefinition(bId, dependencies = Set(cId))
    val c = AppDefinition(cId, dependencies = Set(dId))
    val d = AppDefinition(dId)
    val e = AppDefinition(eId)

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
    val versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10))
    val app: AppDefinition = AppDefinition("/app".toPath, Some("sleep 10"), versionInfo = versionInfo)
    val app2: AppDefinition = AppDefinition("/app2".toPath, Some("cmd2"), versionInfo = versionInfo)
    val app3: AppDefinition = AppDefinition("/app3".toPath, Some("cmd3"), versionInfo = versionInfo)
    val unchanged: AppDefinition = AppDefinition("/unchanged".toPath, Some("unchanged"), versionInfo = versionInfo)

    val apps = Set(app, app2, app3, unchanged)

    val update = Set(
      app.copy(cmd = Some("sleep 30")),
      app2.copy(instances = 10),
      AppDefinition("/app4".toPath, Some("cmd4")),
      unchanged
    )

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

    val versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10))
    val mongo: (AppDefinition, AppDefinition) =
      AppDefinition(mongoId, Some("mng1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy, versionInfo = versionInfo)

    val service: (AppDefinition, AppDefinition) =
      AppDefinition(serviceId, Some("srv1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(
          serviceId, Some("srv2"), dependencies = Set(mongoId), instances = 10, upgradeStrategy = strategy,
          versionInfo = versionInfo
        )

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
    plan.steps(0).actions.toSet should equal (Set(RestartApplication(mongo._2)))
    plan.steps(1).actions.toSet should equal (Set(RestartApplication(service._2)))
  }

  test("when starting apps without dependencies, they are first started and then scaled parallely") {
    Given("an empty group and the same group but now including four independent apps")
    val emptyGroup = Group(id = "/test".toPath)

    val instances: Int = 10

    val apps: Set[AppDefinition] = (1 to 4).map { i =>
      AppDefinition(s"/test/$i".toPath, Some("cmd"), instances = instances)
    }.toSet

    val targetGroup = Group(
      id = "/test".toPath,
      apps = apps,
      groups = Set()
    )

    When("the deployment plan is computed")
    val plan = DeploymentPlan(emptyGroup, targetGroup)

    Then("we get two deployment steps")
    plan.steps should have size 2
    Then("the first with all StartApplication actions")
    plan.steps(0).actions.toSet should equal (apps.map(StartApplication(_, 0)))
    Then("and the second with all ScaleApplication actions")
    plan.steps(1).actions.toSet should equal (apps.map(ScaleApplication(_, instances)))
  }

  test("when updating apps without dependencies, the restarts are executed in the same step") {
    Given("Two application updates with command and scale changes")
    val mongoId = "/test/database/mongo".toPath
    val serviceId = "/test/service/srv1".toPath
    val strategy = UpgradeStrategy(0.75)

    val versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10))

    val mongo =
      AppDefinition(mongoId, Some("mng1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy, versionInfo = versionInfo)

    val service =
      AppDefinition(serviceId, Some("srv1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(serviceId, Some("srv2"), instances = 10, upgradeStrategy = strategy, versionInfo = versionInfo)

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
    plan.steps should have size 1
    plan.steps(0).actions.toSet should equal (Set(RestartApplication(mongo._2), RestartApplication(service._2)))
  }

  test("when updating a group with dependent and independent applications, the correct order is computed") {
    Given("application updates with command and scale changes")
    val mongoId = "/test/database/mongo".toPath
    val serviceId = "/test/service/srv1".toPath
    val appId = "/test/independent/app".toPath
    val strategy = UpgradeStrategy(0.75)

    val versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10))

    val mongo =
      AppDefinition(mongoId, Some("mng1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy, versionInfo = versionInfo)

    val service =
      AppDefinition(serviceId, Some("srv1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
        AppDefinition(serviceId, Some("srv2"), dependencies = Set(mongoId), instances = 10, upgradeStrategy = strategy,
          versionInfo = versionInfo)

    val independent =
      AppDefinition(appId, Some("app1"), instances = 1, upgradeStrategy = strategy) ->
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
    plan.steps should have size (5)

    actionsOf(plan) should have size (6)

    plan.steps(0).actions.toSet should equal (Set(StopApplication(toStop)))
    plan.steps(1).actions.toSet should equal (Set(StartApplication(toStart, 0)))
    plan.steps(2).actions.toSet should equal (Set(RestartApplication(mongo._2), RestartApplication(independent._2)))
    plan.steps(3).actions.toSet should equal (Set(RestartApplication(service._2)))
    plan.steps(4).actions.toSet should equal (Set(ScaleApplication(toStart, 2)))
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
    val versionInfo: FullVersionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10))
    val app = AppDefinition(id = "/test".toPath, cmd = Some("sleep 5"), versionInfo = versionInfo)
    val appNew = app.copy(args = Some(Seq("foo")))

    val from = Group("/".toPath, apps = Set(app))
    val to = from.copy(apps = Set(appNew))

    val plan = DeploymentPlan(from, to)

    plan.steps should not be empty
  }

  // regression test for #1007
  test("Don't restart apps that have not changed") {
    val app = AppDefinition(
      id = "/test".toPath,
      cmd = Some("sleep 5"),
      instances = 1,
      versionInfo = VersionInfo.forNewConfig(Timestamp(10))
    )
    val appNew = app.copy(instances = 1) // no change

    val from = Group("/".toPath, apps = Set(app))
    val to = from.copy(apps = Set(appNew))

    DeploymentPlan(from, to) should be (empty)
  }

  test("Restart apps that have not changed but a new version") {
    val app = AppDefinition(
      id = "/test".toPath,
      cmd = Some("sleep 5"),
      versionInfo = VersionInfo.forNewConfig(Timestamp(10))
    )
    val appNew = app.markedForRestarting

    val from = Group("/".toPath, apps = Set(app))
    val to = from.copy(apps = Set(appNew))

    DeploymentPlan(from, to).steps should have size (1)
    DeploymentPlan(from, to).steps.head should be (DeploymentStep(Seq(RestartApplication(appNew))))
  }

  test("ScaleApplication step is created with TasksToKill") {
    Given("a group with one app")
    val aId = "/test/some/a".toPath
    val oldApp = AppDefinition(aId, versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(10)))

    When("A deployment plan is generated")
    val originalGroup = Group(
      id = "/test".toPath,
      apps = Set(oldApp),
      groups = Set(
        Group("/test/some".toPath, Set(oldApp))
      )
    )

    val newApp = oldApp.copy(instances = 5)
    val targetGroup = Group(
      id = "/test".toPath,
      apps = Set(newApp),
      groups = Set(
        Group("/test/some".toPath, Set(newApp))
      )
    )

    val taskToKill = MarathonTestHelper.stagedTaskForApp(aId)
    val plan = DeploymentPlan(
      original = originalGroup,
      target = targetGroup,
      resolveArtifacts = Seq.empty,
      version = Timestamp.now(),
      toKill = Map(aId -> Set(taskToKill)))

    Then("DeploymentSteps should include ScaleApplication w/ tasksToKill")
    plan.steps should not be empty
    plan.steps.head.actions.head shouldEqual ScaleApplication(newApp, 5, Some(Set(taskToKill)))
  }

  test("Deployment plan allows valid updates for resident tasks") {
    Given("All options are supplied and we have a valid group change")
    val f = new Fixture()

    When("We create a scale deployment")
    val app = f.validResident.copy(instances = 123)
    val group = f.group.copy(apps = Set(app))
    val plan = DeploymentPlan(f.group, group)

    Then("The deployment is valid")
    validate(plan).isSuccess should be(true)
  }

  test("Deployment plan validation fails for invalid changes in resident tasks") {
    Given("All options are supplied and we have a valid group change")
    val f = new Fixture()

    When("We update the upgrade strategy to the default strategy")
    val app2 = f.validResident.copy(upgradeStrategy = AppDefinition.DefaultUpgradeStrategy)
    val group2 = f.group.copy(apps = Set(app2))
    val plan2 = DeploymentPlan(f.group, group2)

    Then("The deployment is not valid")
    validate(plan2).isSuccess should be(false)
  }

  class Fixture {
    def persistentVolume(path: String) = PersistentVolume(path, PersistentVolumeInfo(123), mesos.Volume.Mode.RW)
    val zero = UpgradeStrategy(0, 0)

    def residentApp(id: String, volumes: Seq[PersistentVolume]): AppDefinition = {
      AppDefinition(
        id = PathId(id),
        container = Some(Container(mesos.ContainerInfo.Type.MESOS, volumes)),
        residency = Some(Residency(123, Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT))
      )
    }
    val vol1 = persistentVolume("foo")
    val vol2 = persistentVolume("bla")
    val vol3 = persistentVolume("test")
    val validResident = residentApp("/app1", Seq(vol1, vol2)).copy(upgradeStrategy = zero)
    val group = Group(PathId("/test"), apps = Set(validResident))
  }
}
