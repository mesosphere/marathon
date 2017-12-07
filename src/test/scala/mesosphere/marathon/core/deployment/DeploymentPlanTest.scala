package mesosphere.marathon
package core.deployment

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.VersionInfo._
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ GroupCreation, MarathonTestHelper }
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq

class DeploymentPlanTest extends UnitTest with GroupCreation {

  protected def actionsOf(plan: DeploymentPlan): Seq[DeploymentAction] =
    plan.steps.flatMap(_.actions)

  "Deployment Plan" should {
    "partition a simple group's apps into concurrently deployable subsets" in {
      Given("a group of four apps with some simple dependencies")
      val aId = "/test/database/a".toPath
      val bId = "/test/service/b".toPath
      val cId = "/test/c".toPath
      val dId = "/test/d".toPath

      val a = AppDefinition(aId, cmd = Some("sleep"))
      val b = AppDefinition(bId, dependencies = Set(aId), cmd = Some("sleep"))
      val c = AppDefinition(cId, dependencies = Set(aId), cmd = Some("sleep"))
      val d = AppDefinition(dId, dependencies = Set(bId), cmd = Some("sleep"))

      val rootGroup = createRootGroup(groups = Set(createGroup(
        id = "/test".toPath,
        apps = Map(c.id -> c, d.id -> d),
        groups = Set(
          createGroup("/test/database".toPath, Map(a.id -> a)),
          createGroup("/test/service".toPath, Map(b.id -> b))
        )
      )))

      When("the group's apps are grouped by the longest outbound path")
      val partitionedApps = DeploymentPlan.runSpecsGroupedByLongestPath(Set(a.id, b.id, c.id, d.id), rootGroup)

      Then("three equivalence classes should be computed")
      partitionedApps should have size 3

      partitionedApps.keySet should contain(1)
      partitionedApps.keySet should contain(2)
      partitionedApps.keySet should contain(3)

      partitionedApps(2) should have size 2
    }

    "partition a complex group's apps into concurrently deployable subsets" in {
      Given("a group of four apps with some simple dependencies")
      val aId = "/a".toPath
      val bId = "/b".toPath
      val cId = "/c".toPath
      val dId = "/d".toPath
      val eId = "/e".toPath
      val fId = "/f".toPath

      val a = AppDefinition(aId, dependencies = Set(bId, cId), cmd = Some("sleep"))
      val b = AppDefinition(bId, dependencies = Set(cId), cmd = Some("sleep"))
      val c = AppDefinition(cId, dependencies = Set(dId), cmd = Some("sleep"))
      val d = AppDefinition(dId, cmd = Some("sleep"))
      val e = AppDefinition(eId, cmd = Some("sleep"))

      val rootGroup = createRootGroup(
        apps = Map(a.id -> a, b.id -> b, c.id -> c, d.id -> d, e.id -> e)
      )

      When("the group's apps are grouped by the longest outbound path")
      val partitionedApps = DeploymentPlan.runSpecsGroupedByLongestPath(rootGroup.transitiveAppIds.toSet, rootGroup)

      Then("three equivalence classes should be computed")
      partitionedApps should have size 4

      partitionedApps.keySet should contain(1)
      partitionedApps.keySet should contain(2)
      partitionedApps.keySet should contain(3)
      partitionedApps.keySet should contain(4)

      partitionedApps(1) should have size 2
    }

    "start from empty group" in {
      val app = AppDefinition("/group/app".toPath, instances = 2, cmd = Some("sleep"))
      val from = createRootGroup(groups = Set(createGroup("/group".toPath, Map.empty, groups = Set.empty)))
      val to = createRootGroup(groups = Set(createGroup("/group".toPath, Map(app.id -> app))))
      val plan = DeploymentPlan(from, to)

      actionsOf(plan) should contain(StartApplication(app, 0))
      actionsOf(plan) should contain(ScaleApplication(app, app.instances))
    }

    "start from running group" in {
      val app1 = AppDefinition("app".toPath, Some("sleep 10"))
      val app2 = AppDefinition("app2".toPath, Some("cmd2"))
      val app3 = AppDefinition("app3".toPath, Some("cmd3"))
      val updatedApp1 = AppDefinition("app".toPath, Some("sleep 30"))
      val updatedApp2 = AppDefinition("app2".toPath, Some("cmd2"), instances = 10)
      val app4 = AppDefinition("app4".toPath, Some("cmd4"))
      val apps = Map(app1.id -> app1, app2.id -> app2, app3.id -> app3)
      val update = Map(updatedApp1.id -> updatedApp1, updatedApp2.id -> updatedApp2, app4.id -> app4)

      val from = createRootGroup(groups = Set(createGroup("/group".toPath, apps)))
      val to = createRootGroup(groups = Set(createGroup("/group".toPath, update)))
      val plan = DeploymentPlan(from, to)

      /*
    plan.toStart should have size 1
    plan.toRestart should have size 1
    plan.toScale should have size 1
    plan.toStop should have size 1
    */
    }

    "can compute affected app ids" in {
      val versionInfo = VersionInfo.forNewConfig(Timestamp(10))
      val app: AppDefinition = AppDefinition("/group/app".toPath, Some("sleep 10"), versionInfo = versionInfo)
      val app2: AppDefinition = AppDefinition("/group/app2".toPath, Some("cmd2"), versionInfo = versionInfo)
      val app3: AppDefinition = AppDefinition("/group/app3".toPath, Some("cmd3"), versionInfo = versionInfo)
      val unchanged: AppDefinition = AppDefinition("/group/unchanged".toPath, Some("unchanged"), versionInfo = versionInfo)

      val apps = Map(app.id -> app, app2.id -> app2, app3.id -> app3, unchanged.id -> unchanged)

      val updatedApp = app.copy(cmd = Some("sleep 30"))
      val updatedApp2 = app2.copy(instances = 10)
      val updatedApp4 = AppDefinition("/group/app4".toPath, Some("cmd4"))
      val update = Map(
        updatedApp.id -> updatedApp,
        updatedApp2.id -> updatedApp2,
        updatedApp4.id -> updatedApp4,
        unchanged.id -> unchanged
      )

      val from = createRootGroup(groups = Set(createGroup("/group".toPath, apps)))
      val to = createRootGroup(groups = Set(createGroup("/group".toPath, update)))
      val plan = DeploymentPlan(from, to)

      plan.affectedRunSpecIds should equal(Set("/group/app".toPath, "/group/app2".toPath, "/group/app3".toPath, "/group/app4".toPath))
      plan.isAffectedBy(plan) should equal(right = true)
      plan.isAffectedBy(DeploymentPlan(from, from)) should equal(right = false)
    }

    "when updating a group with dependencies, the correct order is computed" in {

      Given("Two application updates with command and scale changes")
      val mongoId = "/test/database/mongo".toPath
      val serviceId = "/test/service/srv1".toPath
      val strategy = UpgradeStrategy(0.75)

      val versionInfo = VersionInfo.forNewConfig(Timestamp(10))
      val mongo: (AppDefinition, AppDefinition) =
        AppDefinition(mongoId, Some("mng1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
          AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy, versionInfo = versionInfo)

      val service: (AppDefinition, AppDefinition) =
        AppDefinition(serviceId, Some("srv1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
          AppDefinition(
            serviceId, Some("srv2"), dependencies = Set(mongoId), instances = 10, upgradeStrategy = strategy,
            versionInfo = versionInfo
          )

      val from = createRootGroup(groups = Set(createGroup(
        id = "/test".toPath,
        groups = Set(
          createGroup("/test/database".toPath, Map(mongo._1.id -> mongo._1)),
          createGroup("/test/service".toPath, Map(service._1.id -> service._1))
        )
      )
      ))

      val to = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/database".toPath, Map(mongo._2.id -> mongo._2)),
        createGroup("/test/service".toPath, Map(service._2.id -> service._2))
      ))))

      When("the deployment plan is computed")
      val plan = DeploymentPlan(from, to)

      Then("the deployment steps are correct")
      plan.steps should have size 2
      plan.steps(0).actions.toSet should equal(Set(RestartApplication(mongo._2)))
      plan.steps(1).actions.toSet should equal(Set(RestartApplication(service._2)))
    }

    "when starting apps without dependencies, they are first started and then scaled parallely" in {
      Given("an empty group and the same group but now including four independent apps")
      val emptyGroup = createRootGroup(groups = Set(createGroup(id = "/test".toPath)))

      val instances: Int = 10

      val apps: Map[AppDefinition.AppKey, AppDefinition] = (1 to 4).map { i =>
        val app = AppDefinition(s"/test/$i".toPath, Some("cmd"), instances = instances)
        app.id -> app
      }(collection.breakOut)

      val targetGroup = createRootGroup(groups = Set(createGroup(
        id = "/test".toPath,
        apps = apps,
        groups = Set()
      )))

      When("the deployment plan is computed")
      val plan = DeploymentPlan(emptyGroup, targetGroup)

      Then("we get two deployment steps")
      plan.steps should have size 2
      Then("the first with all StartApplication actions")
      plan.steps(0).actions.toSet should equal(apps.mapValues(StartApplication(_, 0)).values.toSet)
      Then("and the second with all ScaleApplication actions")
      plan.steps(1).actions.toSet should equal(apps.mapValues(ScaleApplication(_, instances)).values.toSet)
    }

    "when updating apps without dependencies, the restarts are executed in the same step" in {
      Given("Two application updates with command and scale changes")
      val mongoId = "/test/database/mongo".toPath
      val serviceId = "/test/service/srv1".toPath
      val strategy = UpgradeStrategy(0.75)

      val versionInfo = VersionInfo.forNewConfig(Timestamp(10))

      val mongo =
        AppDefinition(mongoId, Some("mng1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
          AppDefinition(mongoId, Some("mng2"), instances = 8, upgradeStrategy = strategy, versionInfo = versionInfo)

      val service =
        AppDefinition(serviceId, Some("srv1"), instances = 4, upgradeStrategy = strategy, versionInfo = versionInfo) ->
          AppDefinition(serviceId, Some("srv2"), instances = 10, upgradeStrategy = strategy, versionInfo = versionInfo)

      val from = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/database".toPath, Map(mongo._1.id -> mongo._1)),
        createGroup("/test/service".toPath, Map(service._1.id -> service._1))
      ))))

      val to = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/database".toPath, Map(mongo._2.id -> mongo._2)),
        createGroup("/test/service".toPath, Map(service._2.id -> service._2))
      ))))

      When("the deployment plan is computed")
      val plan = DeploymentPlan(from, to)

      Then("the deployment steps are correct")
      plan.steps should have size 1
      plan.steps(0).actions.toSet should equal(Set(RestartApplication(mongo._2), RestartApplication(service._2)))
    }

    "when updating a group with dependent and independent applications, the correct order is computed" in {
      Given("application updates with command and scale changes")
      val mongoId = "/test/database/mongo".toPath
      val serviceId = "/test/service/srv1".toPath
      val appId = "/test/independent/app".toPath
      val strategy = UpgradeStrategy(0.75)

      val versionInfo = VersionInfo.forNewConfig(Timestamp(10))

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

      val toStop = AppDefinition("/test/service/to-stop".toPath, instances = 1, dependencies = Set(mongoId), cmd = Some("sleep"))
      val toStart = AppDefinition("/test/service/to-start".toPath, instances = 2, dependencies = Set(serviceId), cmd = Some("sleep"))

      val from = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/database".toPath, Map(mongo._1.id -> mongo._1)),
        createGroup("/test/service".toPath, Map(service._1.id -> service._1, toStop.id -> toStop)),
        createGroup("/test/independent".toPath, Map(independent._1.id -> independent._1))
      ))))

      val to = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/database".toPath, Map(mongo._2.id -> mongo._2)),
        createGroup("/test/service".toPath, Map(service._2.id -> service._2, toStart.id -> toStart)),
        createGroup("/test/independent".toPath, Map(independent._2.id -> independent._2))
      ))))

      When("the deployment plan is computed")
      val plan = DeploymentPlan(from, to)

      Then("the deployment contains steps for dependent and independent applications")
      plan.steps should have size 5

      actionsOf(plan) should have size 6

      plan.steps(0).actions.toSet should equal(Set(StopApplication(toStop)))
      plan.steps(1).actions.toSet should equal(Set(StartApplication(toStart, 0)))
      plan.steps(2).actions.toSet should equal(Set(RestartApplication(mongo._2), RestartApplication(independent._2)))
      plan.steps(3).actions.toSet should equal(Set(RestartApplication(service._2)))
      plan.steps(4).actions.toSet should equal(Set(ScaleApplication(toStart, 2)))
    }

    "when the only action is to stop an application" in {
      Given("application updates with only the removal of an app")
      val strategy = UpgradeStrategy(0.75)
      val app = AppDefinition("/test/independent/app".toPath, Some("app2"), instances = 3, upgradeStrategy = strategy) -> None
      val from = createRootGroup(
        groups = Set(createGroup("/test".toPath, groups = Set(
          createGroup("/test/independent".toPath, Map(app._1.id -> app._1))
        ))))
      val to = createRootGroup(groups = Set(createGroup("/test".toPath)))

      When("the deployment plan is computed")
      val plan = DeploymentPlan(from, to)

      Then("the deployment contains one step consisting of one stop action")
      plan.steps should have size 1
      plan.steps(0).actions.toSet should be(Set(StopApplication(app._1)))
    }

    // regression test for #765
    "Should create non-empty deployment plan when only args have changed" in {
      val versionInfo: FullVersionInfo = VersionInfo.forNewConfig(Timestamp(10))
      val app = AppDefinition(id = "/test".toPath, cmd = Some("sleep 5"), versionInfo = versionInfo)
      val appNew = app.copy(args = Seq("foo"))

      val from = createRootGroup(apps = Map(app.id -> app))
      val to = from.updateApps(PathId.empty, _ => Map(appNew.id -> appNew), from.version)

      val plan = DeploymentPlan(from, to)

      plan.steps should not be empty
    }

    // regression test for #1007
    "Don't restart apps that have not changed" in {
      val app = AppDefinition(
        id = "/test".toPath,
        cmd = Some("sleep 5"),
        instances = 1,
        versionInfo = VersionInfo.forNewConfig(Timestamp(10))
      )
      val appNew = app.copy(instances = 1) // no change

      val from = createRootGroup(apps = Map(app.id -> app))
      val to = from.updateApps(PathId.empty, _ => Map(appNew.id -> appNew), from.version)

      DeploymentPlan(from, to) should be(empty)
    }

    "Restart apps that have not changed but a new version" in {
      val app = AppDefinition(
        id = "/test".toPath,
        cmd = Some("sleep 5"),
        versionInfo = VersionInfo.forNewConfig(Timestamp(10))
      )
      val appNew = app.markedForRestarting

      val from = createRootGroup(apps = Map(app.id -> app))
      val to = from.updateApps(PathId.empty, _ => Map(appNew.id -> appNew), from.version)

      DeploymentPlan(from, to).steps should have size 1
      DeploymentPlan(from, to).steps.head should be(DeploymentStep(Seq(RestartApplication(appNew))))
    }

    "ScaleApplication step is created with TasksToKill" in {
      Given("a group with one app")
      val aId = "/test/some/a".toPath
      val oldAppA = AppDefinition(aId, versionInfo = VersionInfo.forNewConfig(Timestamp(10)), cmd = Some("sleep"))

      When("A deployment plan is generated")
      val originalGroup = createRootGroup(groups = Set(createGroup(
        id = "/test".toPath,
        groups = Set(
          createGroup("/test/some".toPath, Map(oldAppA.id -> oldAppA))
        )
      )))

      val newAppA = oldAppA.copy(instances = 5)
      val targetGroup = createRootGroup(groups = Set(createGroup(
        id = "/test".toPath,
        groups = Set(
          createGroup("/test/some".toPath, Map(newAppA.id -> newAppA))
        )
      )))

      val instanceToKill = TestInstanceBuilder.newBuilder(aId).addTaskStaged().getInstance()
      val plan = DeploymentPlan(
        original = originalGroup,
        target = targetGroup,
        version = Timestamp.now(),
        toKill = Map(aId -> Seq(instanceToKill)))

      Then("DeploymentSteps should include ScaleApplication w/ tasksToKill")
      plan.steps should not be empty
      plan.steps.head.actions.head shouldEqual ScaleApplication(newAppA, 5, Some(Seq(instanceToKill)))
    }

    "Deployment plan allows valid updates for resident tasks" in {
      Given("All options are supplied and we have a valid group change")
      val f = new Fixture()

      When("We create a scale deployment")
      val app = f.validResident.copy(instances = 123)
      val rootGroup = f.rootGroup.updateApps(PathId.empty, _ => Map(app.id -> app), f.rootGroup.version)
      val plan = DeploymentPlan(f.rootGroup, rootGroup)

      Then("The deployment is valid")
      validate(plan)(f.validator).isSuccess should be(true)
    }

    "Deployment plan validation fails for invalid changes in resident tasks" in {
      Given("All options are supplied and we have a valid group change")
      val f = new Fixture()

      When("We update the upgrade strategy to the default strategy")
      val app2 = f.validResident.copy(upgradeStrategy = AppDefinition.DefaultUpgradeStrategy)
      val rootGroup2 = f.rootGroup.updateApps(PathId("/test"), _ => Map(app2.id -> app2), f.rootGroup.version)
      val plan2 = DeploymentPlan(f.rootGroup, rootGroup2)

      Then("The deployment is not valid")
      validate(plan2)(f.validator).isSuccess should be(false)
    }
  }
  class Fixture {
    def persistentVolume(path: String) = PersistentVolume(path, PersistentVolumeInfo(123), mesos.Volume.Mode.RW)
    val zero = UpgradeStrategy(0, 0)

    def residentApp(id: String, volumes: Seq[PersistentVolume]): AppDefinition = {
      AppDefinition(
        id = PathId(id),
        cmd = Some("foo"),
        container = Some(Container.Mesos(volumes)),
        residency = Some(Residency(123, Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT)),
        unreachableStrategy = UnreachableDisabled
      )
    }
    val vol1 = persistentVolume("foo")
    val vol2 = persistentVolume("bla")
    val vol3 = persistentVolume("test")
    val validResident = residentApp("/test/app1", Seq(vol1, vol2)).copy(upgradeStrategy = zero)
    val rootGroup = createRootGroup(groups = Set(createGroup(PathId("/test"), apps = Map(validResident.id -> validResident))))
    val marathonConf = MarathonTestHelper.defaultConfig()
    val validator = DeploymentPlan.deploymentPlanValidator()
  }
}
