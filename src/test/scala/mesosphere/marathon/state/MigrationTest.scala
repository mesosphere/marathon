package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.StorageVersions._
import mesosphere.util.Mockito
import mesosphere.util.state.{ PersistentEntity, PersistentStore, PersistentStoreManagement }
import org.scalatest.{ FunSuite, Matchers }

import scala.concurrent.Future

class MigrationTest extends FunSuite with Mockito with Matchers {

  test("migrations can be filtered by version") {
    val all = migration.migrations.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
    all should have size migration.migrations.size.toLong

    val none = migration.migrations.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0))
    none should have size 0

    val some = migration.migrations.filter(_._1 < StorageVersions(0, 6, 0))
    some should have size 1
  }

  test("migration calls initialization") {
    trait StoreWithManagement extends PersistentStore with PersistentStoreManagement
    val store = mock[StoreWithManagement]
    val appRepo = mock[AppRepository]
    val groupRepo = mock[GroupRepository]
    val config = mock[MarathonConf]
    store.load("internal:storage:version") returns Future.successful(None)
    store.create(any, any) returns Future.successful(mock[PersistentEntity])
    store.initialize() returns Future.successful(())
    appRepo.apps() returns Future.successful(Seq.empty)
    appRepo.allPathIds() returns Future.successful(Seq.empty)
    groupRepo.group("root") returns Future.successful(None)
    val migrate = new Migration(store, appRepo, groupRepo, config, new Metrics(new MetricRegistry))

    migrate.migrate()
    verify(store, atLeastOnce).initialize()
  }

  def migration = {
    val state = mock[PersistentStore]
    val appRepo = mock[AppRepository]
    val groupRepo = mock[GroupRepository]
    val config = mock[MarathonConf]
    new Migration(state, appRepo, groupRepo, config, new Metrics(new MetricRegistry))
  }
}
