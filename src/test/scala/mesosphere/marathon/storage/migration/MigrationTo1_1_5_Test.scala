package mesosphere.marathon.storage.migration

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.LegacyInMemConfig
import mesosphere.marathon.storage.migration.legacy.MigrationTo1_1_5
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.storage.repository.legacy.{ AppEntityRepository, GroupEntityRepository, PodEntityRepository }
import mesosphere.marathon.test.{ GroupCreation, Mockito }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

class MigrationTo1_1_5_Test extends AkkaUnitTest with Mockito with GroupCreation {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private val maxVersions = 100

  "Migrating broken app groups " should {

    "Not change an empty root group" in {
      val f = new Fixture

      val root: RootGroup = createRootGroup()
      f.oldGroupRepo.storeRoot(root, Nil, Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue should be('empty)
      f.oldGroupRepo.root().futureValue.withNormalizedVersions should be equals root.withNormalizedVersions
    }

    "Not change a correct flat root group e.g. /foo" in {
      val f = new Fixture

      val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
      val root = createRootGroup(
        groups = Set(createGroup("/foo".toPath, Map(app.id -> app)))
      )

      f.oldGroupRepo.storeRoot(root, Seq(app), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals root.withNormalizedVersions
    }

    "Not change a correct nested root group e.g. /foo/bar " in {
      val f = new Fixture

      val app = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"))
      val root = createRootGroup(
        groups = Set(createGroup(
          "/foo".toPath,
          groups = Set(createGroup("/foo/bar".toPath, Map(app.id -> app)))
        ))
      )

      f.oldGroupRepo.storeRoot(root, Seq(app), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals root.withNormalizedVersions
    }

    "Correct an app in the wrong group" in {
      val f = new Fixture

      val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
      val correctRoot = createRootGroup(
        groups = Set(
          createGroup("/foo".toPath, Map(app.id -> app)
          )
        )
      )

      val brokenRoot = createRootGroup(
        apps = Map(app.id -> app),
        groups = Set(
          createGroup("/foo".toPath)
        )
      )

      f.oldGroupRepo.storeRoot(brokenRoot, Seq(app), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
    }

    "Remove an app in the wrong group when having two apps with the same version" in {
      val f = new Fixture

      val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
      val correctRoot = createRootGroup(
        groups = Set(
          createGroup("/foo".toPath, Map(app.id -> app)
          )
        )
      )

      val brokenRoot = createRootGroup(
        apps = Map(app.id -> app),
        groups = Set(
          createGroup("/foo".toPath, Map(app.id -> app))
        )
      )

      f.oldGroupRepo.storeRoot(brokenRoot, Seq(app), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
    }

    "Remove an app with the oldest version when having two apps with the same path but different versions" in {
      val f = new Fixture

      val app1 = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
      val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2)))
      val correctRoot = createRootGroup(
        groups = Set(
          createGroup("/foo".toPath, Map(app2.id -> app2)
          )
        )
      )

      val brokenRoot = createRootGroup(
        apps = Map(app2.id -> app2),
        groups = Set(
          createGroup("/foo".toPath, Map(app1.id -> app1))
        )
      )

      f.oldGroupRepo.storeRoot(brokenRoot, Seq(app1), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app2)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
    }

    "Remove an app with the oldest version when having two apps with the same path but different versions in a nested group" in {
      val f = new Fixture

      val app1 = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
      val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2)))
      val correctRoot = createRootGroup(
        groups = Set(createGroup(
          "/foo".toPath,
          groups = Set(createGroup("/foo/bar".toPath, Map(app2.id -> app2)))
        ))
      )

      val brokenRoot = createRootGroup(
        groups = Set(createGroup("/foo".toPath, Map(app2.id -> app2),
          groups = Set(createGroup("/foo/bar".toPath, Map(app1.id -> app1)))
        ))
      )

      f.oldGroupRepo.storeRoot(brokenRoot, Seq(app1), Nil, Nil, Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app2)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
    }

    "Remove an app with the oldest version when having two apps with different versions and mutliple root groups" in {
      val f = new Fixture

      val app1 = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(5)))
      val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(6)))
      val correctRoot = createRootGroup(
        groups = Set(createGroup(
          "/foo".toPath,
          groups = Set(createGroup("/foo/bar".toPath, Map(app2.id -> app2)))
        )),
        version = Timestamp(0)
      )

      val brokenRootV1 = createRootGroup(
        groups = Set(createGroup("/foo".toPath, Map(app2.id -> app2),
          groups = Set(createGroup("/foo/bar".toPath, Map(app1.id -> app1)))
        )),
        version = Timestamp(0)
      )

      val brokenRootV2 = brokenRootV1.updateVersion(Timestamp(1))

      f.oldGroupRepo.storeRoot(brokenRootV1, Nil, Nil, Nil, Nil).futureValue
      f.oldGroupRepo.storeRootVersion(brokenRootV1, Nil, Nil).futureValue
      f.oldGroupRepo.storeRootVersion(brokenRootV2, Seq(app1, app2), Nil).futureValue

      f.migration.migrate().futureValue

      f.oldAppRepo.all().runWith(Sink.seq).futureValue.head should be(app2)
      val storedRoot = f.oldGroupRepo.root().futureValue
      storedRoot.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
      val versions = f.oldGroupRepo.rootVersions().runWith(Sink.seq).futureValue
      log.info(s"Found root versions: $versions")

      versions.size shouldEqual 2

      f.oldGroupRepo.rootVersion(versions(0)).futureValue.get.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
      f.oldGroupRepo.rootVersion(versions(1)).futureValue.get.withNormalizedVersions should be equals correctRoot.withNormalizedVersions
    }

  }

  class Fixture {
    implicit val metrics = new Metrics(new MetricRegistry)
    val config = LegacyInMemConfig(maxVersions)
    config.store.markOpen()
    val oldAppRepo: AppEntityRepository = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
    val oldPodRepo: PodEntityRepository = PodRepository.legacyRepository(config.entityStore[PodDefinition], maxVersions)
    val oldGroupRepo: GroupEntityRepository = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, oldAppRepo, oldPodRepo)

    val migration = new MigrationTo1_1_5(Some(config))
  }
}
