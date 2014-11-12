package mesosphere.marathon.state

import mesosphere.marathon.MarathonConf
import com.codahale.metrics.MetricRegistry
import org.apache.mesos.state.State
import org.scalatest.{ Matchers, FunSuite }
import org.scalatest.mock.MockitoSugar
import StorageVersions._

class MigrationTest extends FunSuite with MockitoSugar with Matchers {

  test("migrations can be filtered by version") {
    val all = migration.migrations.filter(_._1 > StorageVersions(0, 0, 0)).sortBy(_._1)
    all should have size migration.migrations.size.toLong

    val none = migration.migrations.filter(_._1 > StorageVersions(Int.MaxValue, 0, 0))
    none should have size 0

    val some = migration.migrations.filter(_._1 < StorageVersions(0, 6, 0))
    some should have size 1
  }

  def migration = {
    val state = mock[State]
    val appRepo = mock[AppRepository]
    val groupRepo = mock[GroupRepository]
    val config = mock[MarathonConf]
    new Migration(state, appRepo, groupRepo, config, new MetricRegistry)
  }
}
