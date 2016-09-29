package mesosphere.marathon.storage.migration.legacy.legacy

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp, VersionInfo }
import mesosphere.marathon.storage.LegacyInMemConfig
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository, PodRepository }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.test.MarathonActorSupport
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.ExecutionContext

class MigrationTo0_11Test extends MarathonActorSupport with GivenWhenThen with Matchers {

  class Fixture {
    implicit val ctx = ExecutionContext.global
    implicit lazy val metrics = new Metrics(new MetricRegistry)
    val maxVersions = 25
    lazy val config = LegacyInMemConfig(maxVersions)
    lazy val migration = new MigrationTo0_11(Some(config))
    lazy val appRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
    lazy val podRepo = PodRepository.legacyRepository(config.entityStore[PodDefinition], maxVersions)
    lazy val groupRepo = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, appRepo, podRepo)
  }

  val emptyGroup = Group.empty

  test("empty migration does (nearly) nothing") {
    Given("no apps/groups")
    val f = new Fixture

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("only an empty root Group is created")
    val group = f.groupRepo.root().futureValue
    group.copy(version = emptyGroup.version) should be (emptyGroup)
    f.appRepo.ids().runWith(Sink.seq).futureValue should be('empty)
  }

  test("if an app only exists in the appRepo, it is expunged") {
    Given("one app in appRepo, none in groupRepo")
    val f = new Fixture
    f.appRepo.store(AppDefinition(PathId("/test"))).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("only an empty root Group is created")
    val group = f.groupRepo.root().futureValue
    group.copy(version = emptyGroup.version) should be (emptyGroup)
    f.appRepo.ids().runWith(Sink.seq).futureValue should be('empty)
  }

  test("if an app only exists in the groupRepo, it is created in the appRepo") {
    Given("one app in appRepo, none in groupRepo")
    val f = new Fixture
    val versionInfo = VersionInfo.OnlyVersion(Timestamp(10))
    val app: AppDefinition = AppDefinition(PathId("/test"), versionInfo = versionInfo)
    val groupWithApp = emptyGroup.copy(
      apps = Map(app.id -> app),
      version = versionInfo.version
    )
    f.groupRepo.storeRoot(groupWithApp, Nil, Nil, Nil, Nil).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo has been updated in the group")
    val group = f.groupRepo.root().futureValue
    val appWithFullVersion = app.copy(versionInfo = app.versionInfo.withConfigChange(app.version))
    group should be (groupWithApp.copy(apps = Map(appWithFullVersion.id -> appWithFullVersion)))

    And("the same app has been stored in the appRepo")
    f.appRepo.ids().runWith(Sink.seq).futureValue should be(Seq(PathId("/test")))
    f.appRepo.get(PathId("/test")).futureValue should be(Some(appWithFullVersion))
    f.appRepo.versions(PathId("/test")).runWith(Sink.seq).futureValue should have size (1)
  }

  private[this] def onlyVersion(ts: Long) = VersionInfo.OnlyVersion(Timestamp(ts))

  test("if an app has (different) revisions in the appRepo and the groupRepo, they are combined") {
    Given("one app with multiple versions in appRepo and the newest version in groupRepo")
    val f = new Fixture

    val appV1 = AppDefinition(PathId("/test"), cmd = Some("sleep 1"), instances = 0, versionInfo = onlyVersion(1))
    val appV2Upgrade = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 0, versionInfo = onlyVersion(2))
    f.appRepo.store(appV1).futureValue
    f.appRepo.store(appV2Upgrade).futureValue

    val appV3Scaling = AppDefinition(PathId("/test"), cmd = Some("sleep 2"), instances = 1, versionInfo = onlyVersion(3))
    val groupWithApp = emptyGroup.copy(
      apps = Map(appV3Scaling.id -> appV3Scaling),
      version = Timestamp(3)
    )
    f.groupRepo.storeRoot(groupWithApp, Nil, Nil, Nil, Nil).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo is accurate in the group")
    val correctedAppV1 = appV1.copy(versionInfo = appV1.versionInfo.withConfigChange(appV1.version))
    val correctedAppV2 = appV2Upgrade.copy(versionInfo = correctedAppV1.versionInfo.withConfigChange(appV2Upgrade.version))
    val correctedAppV3 = appV3Scaling.copy(versionInfo = correctedAppV2.versionInfo.withScaleOrRestartChange(appV3Scaling.version))

    val group = f.groupRepo.root().futureValue
    group should be (groupWithApp.copy(apps = Map(correctedAppV3.id -> correctedAppV3)))

    And("the same app has been stored in the appRepo")
    f.appRepo.ids().runWith(Sink.seq).futureValue should be(Seq(PathId("/test")))
    f.appRepo.get(PathId("/test")).futureValue should be(Some(correctedAppV3))
    f.appRepo.versions(PathId("/test")).runWith(Sink.seq).futureValue should have size (3)
    f.appRepo.getVersion(PathId("/test"), correctedAppV1.version.toOffsetDateTime).futureValue should be(Some(correctedAppV1))
    f.appRepo.getVersion(PathId("/test"), correctedAppV2.version.toOffsetDateTime).futureValue should be(Some(correctedAppV2))
    f.appRepo.getVersion(PathId("/test"), correctedAppV3.version.toOffsetDateTime).futureValue should be(Some(correctedAppV3))
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
      apps = Map(appV3Scaling.id -> appV3Scaling),
      version = Timestamp(3)
    )
    f.groupRepo.storeRoot(groupWithApp, Nil, Nil, Nil, Nil).futureValue

    When("migrating")
    f.migration.migrateApps().futureValue

    Then("the versionInfo is accurate in the group")
    val correctedAppV1 = appV1.copy(versionInfo = appV1.versionInfo.withConfigChange(appV1.version))
    val correctedAppV2 = appV2Upgrade.copy(versionInfo = correctedAppV1.versionInfo.withConfigChange(appV2Upgrade.version))
    val correctedAppV3 = appV3Scaling.copy(versionInfo = correctedAppV2.versionInfo.withScaleOrRestartChange(appV3Scaling.version))

    val group = f.groupRepo.root().futureValue
    group should be (groupWithApp.copy(apps = Map(correctedAppV3.id -> correctedAppV3)))

    And("the same app has been stored in the appRepo")
    f.appRepo.ids().runWith(Sink.seq).futureValue should be(Seq(PathId("/test")))
    f.appRepo.get(PathId("/test")).futureValue should be(Some(correctedAppV3))
    f.appRepo.versions(PathId("/test")).runWith(Sink.seq).futureValue should have size (3)
    f.appRepo.getVersion(PathId("/test"), correctedAppV1.version.toOffsetDateTime).futureValue should be(Some(correctedAppV1))
    f.appRepo.getVersion(PathId("/test"), correctedAppV2.version.toOffsetDateTime).futureValue should be(Some(correctedAppV2))
    f.appRepo.getVersion(PathId("/test"), correctedAppV3.version.toOffsetDateTime).futureValue should be(Some(correctedAppV3))
  }
}
