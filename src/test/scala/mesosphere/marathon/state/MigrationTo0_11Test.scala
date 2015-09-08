package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration._

class MigrationTo0_11Test extends MarathonSpec with GivenWhenThen with Matchers {
  import mesosphere.FutureTestSupport._

  class Fixture {
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val store = new InMemoryStore()

    lazy val groupStore = new MarathonStore[Group](store, metrics, () => Group.empty, prefix = "group:")
    lazy val groupRepo = new GroupRepository(groupStore, maxVersions = None, metrics)
    lazy val appStore = new MarathonStore[AppDefinition](store, metrics, () => AppDefinition(), prefix = "app:")
    lazy val appRepo = new AppRepository(appStore, maxVersions = None, metrics)

    lazy val migration = new MigrationTo0_11(groupRepository = groupRepo, appRepository = appRepo)
  }

  val emptyGroup = Group.empty

  implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(3, Seconds))

  test("empty migration does (nearly) nothing") {
    Given("no apps/groups")
    val f = new Fixture

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("only an empty root Group is created")
    val maybeGroup: Option[Group] = f.groupRepo.rootGroup().futureValue
    maybeGroup.map(_.copy(version = emptyGroup.version)) should be (Some(emptyGroup))
    f.appRepo.allPathIds().futureValue should be('empty)
  }

  test("if an app only exists in the appRepo, it is expunged") {
    Given("one app in appRepo, none in groupRepo")
    val f = new Fixture
    f.appRepo.store(AppDefinition(PathId("/test"))).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("only an empty root Group is created")
    val maybeGroup: Option[Group] = Await.result(f.groupRepo.rootGroup(), 3.seconds)
    maybeGroup.map(_.copy(version = emptyGroup.version)) should be (Some(emptyGroup))
    f.appRepo.allPathIds().futureValue should be('empty)
  }

  test("if an app only exists in the groupRepo, it is created in the appRepo") {
    Given("one app in appRepo, none in groupRepo")
    val f = new Fixture
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(10))
    val app: AppDefinition = AppDefinition(PathId("/test"), versionInfo = versionInfo)
    val groupWithApp = emptyGroup.copy(
      apps = Set(app),
      version = versionInfo.version
    )
    f.groupRepo.store(f.groupRepo.zkRootName, groupWithApp).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo has been updated in the group")
    val maybeGroup: Option[Group] = Await.result(f.groupRepo.rootGroup(), 3.seconds)
    val appWithFullVersion = app.copy(versionInfo = app.versionInfo.withConfigChange(app.version))
    maybeGroup should be (Some(groupWithApp.copy(apps = Set(appWithFullVersion))))

    And("the same app has been stored in the appRepo")
    f.appRepo.allPathIds().futureValue should be(Seq(PathId("/test")))
    f.appRepo.currentVersion(PathId("/test")).futureValue should be(Some(appWithFullVersion))
    f.appRepo.listVersions(PathId("/test")).futureValue should have size (1)
  }

  private[this] def onlyVersion(ts: Long) = AppDefinition.VersionInfo.OnlyVersion(Timestamp(ts))

  test("if an app has (different) revisions in the appRepo and the groupRepo, they are combined") {
    Given("one app with multiple versions in appRepo and the newest version in groupRepo")
    val f = new Fixture

    val appV1 = AppDefinition(PathId("/test"), cmd = Some("sleep 1"), instances = 0, versionInfo = onlyVersion(1))
    val appV2Upgrade = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 0, versionInfo = onlyVersion(2))
    f.appRepo.store(appV1).futureValue
    f.appRepo.store(appV2Upgrade).futureValue

    val appV3Scaling = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 1, versionInfo = onlyVersion(3))
    val groupWithApp = emptyGroup.copy(
      apps = Set(appV3Scaling),
      version = Timestamp(3)
    )
    f.groupRepo.store(f.groupRepo.zkRootName, groupWithApp).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo is accurate in the group")
    val correctedAppV1 = appV1.copy(versionInfo = appV1.versionInfo.withConfigChange(appV1.version))
    val correctedAppV2 = appV2Upgrade.copy(versionInfo = correctedAppV1.versionInfo.withConfigChange(appV2Upgrade.version))
    val correctedAppV3 = appV3Scaling.copy(versionInfo = correctedAppV2.versionInfo.withScaleOrRestartChange(appV3Scaling.version))

    val maybeGroup: Option[Group] = f.groupRepo.rootGroup().futureValue
    maybeGroup should be (Some(groupWithApp.copy(apps = Set(correctedAppV3))))

    And("the same app has been stored in the appRepo")
    f.appRepo.allPathIds().futureValue should be(Seq(PathId("/test")))
    f.appRepo.currentVersion(PathId("/test")).futureValue should be(Some(correctedAppV3))
    f.appRepo.listVersions(PathId("/test")).futureValue should have size (3)
    f.appRepo.app(PathId("/test"), correctedAppV1.version).futureValue should be(Some(correctedAppV1))
    f.appRepo.app(PathId("/test"), correctedAppV2.version).futureValue should be(Some(correctedAppV2))
    f.appRepo.app(PathId("/test"), correctedAppV3.version).futureValue should be(Some(correctedAppV3))
  }

  test("if an app has revisions in the appRepo and the latest in the groupRepo, they are combined correctly") {
    Given("one app with multiple versions in appRepo and the newest version in groupRepo")
    val f = new Fixture

    val appV1 = AppDefinition(PathId("/test"), cmd = Some("sleep 1"), instances = 0, versionInfo = onlyVersion(1))
    val appV2Upgrade = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 0, versionInfo = onlyVersion(2))
    val appV3Scaling = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 1, versionInfo = onlyVersion(3))

    f.appRepo.store(appV1).futureValue
    f.appRepo.store(appV2Upgrade).futureValue
    f.appRepo.store(appV3Scaling).futureValue

    val groupWithApp = emptyGroup.copy(
      apps = Set(appV3Scaling),
      version = Timestamp(3)
    )
    f.groupRepo.store(f.groupRepo.zkRootName, groupWithApp).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo is accurate in the group")
    val correctedAppV1 = appV1.copy(versionInfo = appV1.versionInfo.withConfigChange(appV1.version))
    val correctedAppV2 = appV2Upgrade.copy(versionInfo = correctedAppV1.versionInfo.withConfigChange(appV2Upgrade.version))
    val correctedAppV3 = appV3Scaling.copy(versionInfo = correctedAppV2.versionInfo.withScaleOrRestartChange(appV3Scaling.version))

    val maybeGroup: Option[Group] = f.groupRepo.rootGroup().futureValue
    maybeGroup should be (Some(groupWithApp.copy(apps = Set(correctedAppV3))))

    And("the same app has been stored in the appRepo")
    f.appRepo.allPathIds().futureValue should be(Seq(PathId("/test")))
    f.appRepo.currentVersion(PathId("/test")).futureValue should be(Some(correctedAppV3))
    f.appRepo.listVersions(PathId("/test")).futureValue should have size (3)
    f.appRepo.app(PathId("/test"), correctedAppV1.version).futureValue should be(Some(correctedAppV1))
    f.appRepo.app(PathId("/test"), correctedAppV2.version).futureValue should be(Some(correctedAppV2))
    f.appRepo.app(PathId("/test"), correctedAppV3.version).futureValue should be(Some(correctedAppV3))
  }
}
