package mesosphere.marathon
package storage.migration.legacy

import akka.Done
import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.Constraint.Operator._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, PathId }
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, GroupRepository, PodRepository }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

class MigrationTo_1_40Test extends AkkaUnitTest {
  implicit val metrics = new Metrics(new MetricRegistry)

  def constraint(
    field: String,
    operator: Operator,
    value: Option[String] = None): Constraint = {
    val builder = Constraint.newBuilder().setField(field)
    builder.setOperator(operator)
    value.foreach(builder.setValue)
    builder.build()
  }

  "Migration to 1.3.6" when {
    "no apps/roots/plans have any broken constraints" should {
      val appRepo = mock[AppRepository]
      val groupRepo = mock[GroupRepository]
      val deployRepo = mock[DeploymentRepository]
      val podRepo = mock[PodRepository]

      "not do anything" in {
        appRepo.all() returns Source.single(AppDefinition(id = PathId("abc")))
        groupRepo.root() returns Future.successful(Group.empty)
        deployRepo.all() returns Source.empty[DeploymentPlan]

        new MigrationTo_1_4_0(None).migrate(appRepo, groupRepo, deployRepo).futureValue

        verify(appRepo).all()
        verify(groupRepo).root()
        verify(deployRepo).all()
        noMoreInteractions(appRepo, podRepo, groupRepo, deployRepo)
      }
    }

    "app has broken constraints" should {
      val appRepo = mock[AppRepository]
      val podRepo = mock[PodRepository]
      val groupRepo = mock[GroupRepository]
      val deployRepo = mock[DeploymentRepository]

      "fix '*' regex's, remove bad regex's and preserve non-broken constraints" ignore {
        val badApp = AppDefinition(id = PathId("/badApp"), constraints = Set(
          constraint("hostname", LIKE, Some("*")),
          constraint("hostname", UNLIKE, Some("*")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+")),
          constraint("hostname", LIKE, Some("+")), // not a valid regex
          constraint("hostname", UNLIKE, Some("+")),
          constraint("hostname", GROUP_BY, None)))
        val goodApp = AppDefinition(id = PathId("/goodApp"))
        val root = Group(id = PathId("/"), apps = Map(badApp.id -> badApp, goodApp.id -> goodApp),
          groups = Set(Group(id = PathId("/a"), apps = Map(PathId("/a/bad") -> badApp, PathId("/a/bad") -> goodApp)))
        )
        val badPlan = DeploymentPlan(root, root)
        val goodPlan = DeploymentPlan(Group.empty, Group.empty)

        appRepo.all() returns Source(Seq(badApp, goodApp))
        groupRepo.root() returns Future.successful(root)
        deployRepo.all() returns Source(Seq(goodPlan, badPlan))

        appRepo.store(any) returns Future.successful(Done)
        groupRepo.storeRoot(any, any, any, any, any) returns Future.successful(Done)
        deployRepo.store(any) returns Future.successful(Done)

        new MigrationTo_1_4_0(None).migrate(appRepo, groupRepo, deployRepo).futureValue

        val fixedApp = AppDefinition(id = PathId("/badApp"), constraints = Set(
          constraint("hostname", LIKE, Some(".*")),
          constraint("hostname", UNLIKE, Some(".*")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+")),
          constraint("hostname", GROUP_BY, None)))
        val fixedRoot = Group(id = PathId("/"), apps = Map(badApp.id -> fixedApp, goodApp.id -> goodApp), pods = Map.empty,
          groups = Set(Group(id = PathId("/a"), apps = Map(PathId("/a/bad") -> fixedApp, PathId("/a/bad") -> goodApp),
            pods = Map.empty, dependencies = Set.empty,
            version = root.version)),
          dependencies = Set.empty,
          version = root.version
        )
        val fixedPlan = badPlan.copy(original = fixedRoot, target = fixedRoot)

        verify(appRepo).all()
        verify(groupRepo).root()
        verify(deployRepo).all()
        verify(appRepo).store(fixedApp)
        verify(groupRepo).storeRoot(fixedRoot, Seq(fixedApp), Nil, Nil, Nil)
        verify(deployRepo).store(fixedPlan)
        noMoreInteractions(appRepo, groupRepo, deployRepo)
      }
    }
  }
}
