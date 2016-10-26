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

  def constraint(field: String,
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

      "fix '*' regex's, remove bad regex's and preserve non-broken constraints" in {
        val badApp = AppDefinition(id = PathId("/badApp"), constraints = Set(constraint("hostname", LIKE, Some("*")),
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

        appRepo.apps() returns Future.successful(Iterable(badApp, goodApp))
        groupRepo.rootGroup() returns Future.successful(Some(root))
        deployRepo.all() returns Future.successful(Seq(goodPlan, badPlan))

        appRepo.store(any) returns Future.successful(goodApp)
        groupRepo.store(any, any) returns Future.successful(root)
        deployRepo.store(any) returns Future.successful(badPlan)

        new MigrationTo_1_3_5(appRepo, groupRepo, deployRepo).migrate().futureValue

        val fixedApp = AppDefinition(id = PathId("/badApp"), constraints = Set(constraint("hostname", LIKE, Some(".*")),
          constraint("hostname", UNLIKE, Some(".*")),
          constraint("hostname", LIKE, Some("\\w+")),
          constraint("hostname", UNLIKE, Some("\\w+")),
          constraint("hostname", GROUP_BY, None)))
        val fixedRoot = Group(id = PathId("/"), apps = Map(badApp.id -> fixedApp, goodApp.id -> goodApp),
          groups = Set(Group(id = PathId("/a"), apps = Map(PathId("/a/bad") -> fixedApp, PathId("/a/bad") -> goodApp),
            version = root.version)),
          version = root.version
        )
        val fixedPlan = badPlan.copy(original = fixedRoot, target = fixedRoot)

        verify(appRepo).apps()
        verify(groupRepo).rootGroup()
        verify(deployRepo).all()
        verify(appRepo).store(fixedApp)
        verify(groupRepo).store(GroupRepository.zkRootName, fixedRoot)
        verify(deployRepo).store(fixedPlan)
        noMoreInteractions(appRepo, groupRepo, deployRepo)
      }
    }
  }
}
