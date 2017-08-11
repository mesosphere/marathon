package mesosphere.marathon.state

import mesosphere.UnitTest
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.Constraint.Operator._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future
import scala.collection.immutable.Seq

class MigrationTo_1_3_5Test extends UnitTest with Mockito {

  def constraint(
    field: String,
    operator: Operator,
    value: Option[String] = None): Constraint = {
    val builder = Protos.Constraint.newBuilder().setField(field)
    builder.setOperator(operator)
    value.foreach(builder.setValue)
    builder.build()
  }

  "Migration to 1.3.5" when {
    "no apps/roots/plans have any broken constraints" should {
      val appRepo = mock[AppRepository]
      val groupRepo = mock[GroupRepository]
      val deployRepo = mock[DeploymentRepository]

      "not do anything" in {
        appRepo.apps() returns Future.successful(Iterable(AppDefinition()))
        groupRepo.rootGroup() returns Future.successful(Some(Group.empty))
        deployRepo.all() returns Future.successful(Seq.empty[DeploymentPlan])

        new MigrationTo_1_3_5(appRepo, groupRepo, deployRepo).migrate().futureValue

        verify(appRepo).apps()
        verify(groupRepo).rootGroup()
        verify(deployRepo).all()
        noMoreInteractions(appRepo, groupRepo, deployRepo)
      }
    }

    "app has broken constraints" should {
      val appRepo = mock[AppRepository]
      val groupRepo = mock[GroupRepository]
      val deployRepo = mock[DeploymentRepository]

      "fix malformed constraints, remove invalid ones and preserve valid ones" in {
        val badApp = AppDefinition(id = PathId("/badApp"), constraints = Set(
          constraint("hostname", GROUP_BY, Some("str")),
          constraint("hostname", LIKE, None),
          constraint("hostname", LIKE, Some("+")), // not a valid regex
          constraint("hostname", UNLIKE, None),
          constraint("hostname", UNLIKE, Some("+")),

          // valid constraints
          constraint("hostname", UNIQUE, None),
          constraint("hostname", CLUSTER, Some("host")),
          constraint("hostname", GROUP_BY, None),
          constraint("hostname", GROUP_BY, Some("3")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+"))))

        val adjustedApp = AppDefinition(id = PathId("/adjustedApp"), constraints = Set(
          constraint("hostname", UNIQUE, Some("")),
          constraint("hostname", UNIQUE, Some("not-used")),
          constraint("hostname", GROUP_BY, Some("")),
          constraint("hostname", LIKE, Some("*")),
          constraint("hostname", UNLIKE, Some("*")),

          // valid constraints
          constraint("hostname", CLUSTER, Some("host")),
          constraint("hostname", GROUP_BY, None),
          constraint("hostname", GROUP_BY, Some("3")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+"))))

        val goodApp = AppDefinition(id = PathId("/goodApp"))
        val root = Group(
          id = PathId("/"),
          apps = Map(badApp.id -> badApp, adjustedApp.id -> adjustedApp, goodApp.id -> goodApp),
          groups = Set(Group(
            id = PathId("/a"),
            apps = Map(PathId("/a/bad") -> badApp, PathId("/a/adjusted") -> adjustedApp, PathId("/a/good") -> goodApp)))
        )
        val badPlan = DeploymentPlan(root, root)
        val goodPlan = DeploymentPlan(Group.empty, Group.empty)

        appRepo.apps() returns Future.successful(Iterable(badApp, adjustedApp, goodApp))
        groupRepo.rootGroup() returns Future.successful(Some(root))
        deployRepo.all() returns Future.successful(Seq(goodPlan, badPlan))

        appRepo.store(any) returns Future.successful(goodApp)
        groupRepo.store(any, any) returns Future.successful(root)
        deployRepo.store(any) returns Future.successful(badPlan)

        new MigrationTo_1_3_5(appRepo, groupRepo, deployRepo).migrate().futureValue

        val fixedBadApp = AppDefinition(id = PathId("/badApp"), constraints = Set(
          constraint("hostname", UNIQUE, None),
          constraint("hostname", CLUSTER, Some("host")),
          constraint("hostname", GROUP_BY, None),
          constraint("hostname", GROUP_BY, Some("3")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+"))))

        val fixedAdjustedApp = AppDefinition(id = PathId("/adjustedApp"), constraints = Set(
          // adjusted constraints
          constraint("hostname", UNIQUE, None),
          constraint("hostname", LIKE, Some(".*")),
          constraint("hostname", UNLIKE, Some(".*")),

          // valid constraints
          constraint("hostname", CLUSTER, Some("host")),
          constraint("hostname", GROUP_BY, None),
          constraint("hostname", GROUP_BY, Some("3")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+"))))

        val fixedRoot = Group(
          id = PathId("/"),
          apps = Map(badApp.id -> fixedBadApp, adjustedApp.id -> fixedAdjustedApp, goodApp.id -> goodApp),
          groups = Set(Group(
            id = PathId("/a"),
            apps = Map(PathId("/a/bad") -> fixedBadApp, PathId("/a/adjusted") -> fixedAdjustedApp,
              PathId("/a/bad") -> goodApp),
            version = root.version)),
          version = root.version
        )
        val fixedPlan = badPlan.copy(original = fixedRoot, target = fixedRoot)

        verify(appRepo).apps()
        verify(groupRepo).rootGroup()
        verify(deployRepo).all()
        verify(appRepo).store(fixedBadApp)
        verify(appRepo).store(fixedAdjustedApp)
        verify(groupRepo).store(GroupRepository.zkRootName, fixedRoot)
        verify(deployRepo).store(fixedPlan)
        noMoreInteractions(appRepo, groupRepo, deployRepo)
      }
    }
  }
}
