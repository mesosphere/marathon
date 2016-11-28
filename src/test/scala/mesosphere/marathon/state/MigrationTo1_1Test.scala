package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.{MarathonSpec, MarathonTestHelper}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.util.state.memory.InMemoryStore
import org.scalatest.{GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory
import mesosphere.marathon.state.PathId._

class MigrationTo1_1Test extends MarathonSpec with GivenWhenThen with Matchers {
  import mesosphere.FutureTestSupport._

  private[this] val log = LoggerFactory.getLogger(getClass)

  private val id = GroupRepository.zkRootName

  test("Migrating broken app groups should not change an empty root group") {
    val f = new Fixture

    val root: Group = Group.empty
    f.groupRepo.store(id, root).futureValue

    f.migration.migrate().futureValue

    f.groupRepo.rootGroup().futureValue.get.withNormalizedVersion should be equals root.withNormalizedVersion
  }

  test("Migrating broken app groups should not change a correct flat root group e.g. /foo") {
    val f = new Fixture

    val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
    val root = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath, Set(app)).copy(version = Timestamp(0)) )
    ).copy(version = Timestamp(1))

    f.groupRepo.store(id, root).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals root.withNormalizedVersion
  }

  test("Migrating broken app groups should not change a correct nested root group e.g. /foo/bar ") {
    val f = new Fixture

    val app = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"))
    val root = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath,
        groups = Set(Group("/foo/bar".toPath, Set(app) ))
      ))
    )

    f.groupRepo.store(id, root).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals root.withNormalizedVersion
  }

  test("Migrating broken app groups should correct an app in the wrong group") {
    val f = new Fixture

    val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
    val correctRoot = Group(
      id = Group.empty.id,
      groups = Set(
        Group("/foo".toPath, Set(app)
        )
      )
    )

    val brokenRoot = Group(
      id = Group.empty.id,
      apps = Set(app),
      groups = Set(
        Group("/foo".toPath)
      )
    )

    f.groupRepo.store(id, brokenRoot).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
  }

  test("Migrating broken app groups should remove an app in the wrong group when having two apps with the same version") {
    val f = new Fixture

    val app = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"))
    val correctRoot = Group(
      id = Group.empty.id,
      groups = Set(
        Group("/foo".toPath, Set(app)
        )
      )
    )

    val brokenRoot = Group(
      id = Group.empty.id,
      apps = Set(app),
      groups = Set(
        Group("/foo".toPath, Set(app))
      )
    )

    f.groupRepo.store(id, brokenRoot).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
  }

  test("Migrating broken app groups should remove an app with the oldest version when having two apps with the same path but different versions") {
    val f = new Fixture

    val app1 = AppDefinition("/foo/bar".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
    val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2)))
    val correctRoot = Group(
      id = Group.empty.id,
      groups = Set(
        Group("/foo".toPath, Set(app2)
        )
      )
    )

    val brokenRoot = Group(
      id = Group.empty.id,
      apps = Set(app2),
      groups = Set(
        Group("/foo".toPath, Set(app1))
      )
    )

    f.groupRepo.store(id, brokenRoot).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
  }

  test("Migrating broken app groups should remove an app with the oldest version when having two apps with the same path but different versions in a nested group") {
    val f = new Fixture

    val app1 = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
    val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2)))
    val correctRoot = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath,
        groups = Set(Group("/foo/bar".toPath, Set(app2) ))
      ))
    )

    val brokenRoot = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath, Set(app2),
        groups = Set(Group("/foo/bar".toPath, Set(app1) ))
      ))
    )

    f.groupRepo.store(id, brokenRoot).futureValue

    f.migration.migrate().futureValue

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
  }

  test("Migrating broken app groups should remove an app with the oldest version when having two apps with different versions and mutliple root groups") {
    val f = new Fixture

    val app1 = AppDefinition("/foo/bar/bazz".toPath, cmd = Some("cmd"), versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
    val app2 = app1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2)))
    val correctRoot = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath,
        groups = Set(Group("/foo/bar".toPath, Set(app2) ))
      ))
    )

    val brokenRootV1 = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath,
        groups = Set(Group("/foo/bar".toPath ))
      )),
      version = Timestamp.apply(1)
    )

    val brokenRootV2 = Group(
      id = Group.empty.id,
      groups = Set(Group("/foo".toPath, Set(app2),
        groups = Set(Group("/foo/bar".toPath, Set(app1) ))
      )),
      version = Timestamp.apply(2)
    )

    f.groupRepo.store(id, brokenRootV1).futureValue
    f.groupRepo.store(id, brokenRootV2).futureValue

    f.migration.migrate().futureValue

    import scala.concurrent.ExecutionContext.Implicits.global

    val storedRoot = f.groupRepo.rootGroup().futureValue.get
    storedRoot.withNormalizedVersion should be equals correctRoot.withNormalizedVersion

    val storedVersions = f.groupRepo.listVersions(id).map(d => d.toSeq.sorted).futureValue
    storedVersions.size shouldEqual 2
    f.groupRepo.group(id, storedVersions(0)).futureValue.get.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
    f.groupRepo.group(id, storedVersions(1)).futureValue.get.withNormalizedVersion should be equals correctRoot.withNormalizedVersion
  }

  class Fixture {
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val store = new InMemoryStore()

    lazy val groupStore = new MarathonStore[Group](store, metrics, () => Group.empty, prefix = "group:")
    lazy val groupRepo = new GroupRepository(groupStore, maxVersions = None, metrics)
    lazy val appStore = new MarathonStore[AppDefinition](store, metrics, () => AppDefinition(), prefix = "app:")
    lazy val appRepo = new AppRepository(appStore, maxVersions = None, metrics)

    lazy val migration = new MigrationTo1_1(groupRepository = groupRepo, appRepository = appRepo, conf = MarathonTestHelper.defaultConfig())
  }
}
