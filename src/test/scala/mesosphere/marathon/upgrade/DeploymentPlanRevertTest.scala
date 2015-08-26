package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.scalatest.{ GivenWhenThen, Matchers }
import mesosphere.marathon.state.PathId._

import scala.collection.SortedSet

class DeploymentPlanRevertTest extends MarathonSpec with Matchers with GivenWhenThen {
  private def normalizeVersions(group: Group): Group = {
    group.withNormalizedVersion.copy(
      apps = group.apps.map(_.copy(versionInfo = AppDefinition.VersionInfo.NoVersion)),
      groups = group.groups.map(normalizeVersions)
    )
  }

  /**
    * An assert equals which provides better feedback about what's different for groups.
    */
  private def assertEqualsExceptVersion(expectedOrig: Group, actualOrig: Group): Unit = {
    val expected: Group = normalizeVersions(expectedOrig)
    val actual: Group = normalizeVersions(actualOrig)

    if (expected != actual) {
      val actualGroupIds = actual.transitiveGroups.map(_.id)
      val expectedGroupIds = expected.transitiveGroups.map(_.id)

      val unexpectedGroupIds = actualGroupIds -- expectedGroupIds
      val missingGroupIds = expectedGroupIds -- actualGroupIds

      withClue(s"unexpected groups $unexpectedGroupIds, missing groups $missingGroupIds: ") {
        actualGroupIds should equal(expectedGroupIds)
      }

      for (groupId <- expectedGroupIds) {
        withClue(s"for group id $groupId") {
          actual.group(groupId).map(_.withoutChildren) should equal(expected.group(groupId).map(_.withoutChildren))
        }
      }

      val actualAppIds = actual.transitiveApps.map(_.id)
      val expectedAppIds = expected.transitiveApps.map(_.id)

      val unexpectedAppIds = actualAppIds -- expectedAppIds
      val missingAppIds = expectedAppIds -- actualAppIds

      withClue(s"unexpected apps $unexpectedAppIds, missing apps $missingAppIds: ") {
        actualAppIds should equal(expectedAppIds)
      }

      for (appId <- expectedAppIds) {
        withClue(s"for app id $appId") {
          actual.app(appId) should equal(expected.app(appId))
        }
      }

      // just in case we missed differences
      actual should equal(expected)
    }
  }

  test("Revert app addition") {
    Given("an unrelated group")
    val unrelatedGroup = {
      val id = "unrelated".toRootPath
      Group(
        id,
        apps = Set(
          AppDefinition(id / "app1"),
          AppDefinition(id / "app2")
        )
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(unrelatedGroup)
    )

    When("we add an unrelated app and try to revert that without concurrent changes")
    val target = original.updateApp("test".toPath, _ => AppDefinition("test".toPath), Timestamp.now())
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  test("Revert app removal") {
    Given("an existing group with apps")
    val changeme = {
      val id = "changeme".toRootPath
      Group(
        id,
        apps = Set(
          AppDefinition(id / "app1"),
          AppDefinition(id / "app2")
        )
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(changeme)
    )

    When("we remove an app and try to revert that without concurrent changes")
    val appId = "/changeme/app1".toRootPath
    val target = original.update(appId.parent, _.removeApplication(appId), Timestamp.now())
    target.app(appId) should be('empty)
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  test("Revert group addition") {
    Given("an unrelated group")
    val unrelatedGroup = {
      val id = "unrelated".toRootPath
      Group(
        id,
        apps = Set(
          AppDefinition(id / "app1"),
          AppDefinition(id / "app2")
        )
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(unrelatedGroup)
    )

    When("we add a group and try to revert that without concurrent changes")
    val target = original.update("test".toPath, _ => Group("supergroup".toRootPath), Timestamp.now())
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  test("Revert removing a group without apps") {
    Given("a group")
    val changeme = {
      val id = "changeme".toRootPath
      Group(
        id,
        apps = Set()
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(changeme)
    )

    When("we remove the group and try to revert that without concurrent changes")
    val target = original.remove("changeme".toRootPath)
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  test("Revert removing a group with apps") {
    Given("a group")
    val changeme = {
      val id = "changeme".toRootPath
      Group(
        id,
        apps = Set(
          AppDefinition(id / "app1"),
          AppDefinition(id / "app2")
        )
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(changeme)
    )

    When("we remove the group and try to revert that without concurrent changes")
    val target = original.remove("changeme".toRootPath)
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  test("Revert group dependency changes") {
    Given("an existing group with apps")
    val existingGroup = {
      val id = "changeme".toRootPath
      Group(
        id,
        dependencies = Set(
          "othergroup1".toRootPath,
          "othergroup2".toRootPath
        ),
        apps = Set(
          AppDefinition(id / "app1"),
          AppDefinition(id / "app2")
        )
      )
    }

    val original = Group(
      PathId.empty,
      groups = Set(
        Group("othergroup1".toRootPath),
        Group("othergroup2".toRootPath),
        Group("othergroup3".toRootPath),
        existingGroup
      )
    )

    When("we change the dependencies to the existing group")
    val target = original.update(
      existingGroup.id,
      _.copy(dependencies = Set(
        "othergroup2".toRootPath,
        "othergroup3".toRootPath
      )),
      Timestamp.now())
    val plan = DeploymentPlan(original, target)
    val revertToOriginal = plan.revert(target)

    Then("we get back the original definitions")
    assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
  }

  val existingGroup = {
    val id = "changeme".toRootPath
    Group(
      id,
      dependencies = Set(
        "othergroup1".toRootPath,
        "othergroup2".toRootPath
      ),
      apps = Set(
        AppDefinition(id / "app1"),
        AppDefinition(id / "app2")
      )
    )
  }

  val original = Group(
    PathId.empty,
    groups = Set(
      Group("othergroup1".toRootPath),
      Group("othergroup2".toRootPath),
      Group("othergroup3".toRootPath),
      existingGroup
    )
  )

  testWithConcurrentChange(original)(
    removeApp("/changeme/app1"),
    // unrelated app changes
    addApp("/changeme/app3"),
    addApp("/other/app4"),
    removeApp("/changeme/app2")
  ) {
      Group(
        PathId.empty,
        groups = Set(
          Group("othergroup1".toRootPath),
          Group("othergroup2".toRootPath),
          Group("othergroup3".toRootPath),
          {
            val id = "other".toRootPath
            Group(
              id,
              apps = Set(AppDefinition(id / "app4")) // app4 was added
            )
          },
          {
            val id = "changeme".toRootPath
            Group(
              id,
              dependencies = Set(
                "othergroup1".toRootPath,
                "othergroup2".toRootPath
              ),
              apps = Set(
                AppDefinition(id / "app1"), // app1 was kept
                // app2 was removed
                AppDefinition(id / "app3") // app3 was added
              )
            )
          }
        )
      )
    }

  testWithConcurrentChange(original)(
    changeGroupDependencies("/withdeps", add = Seq("/a", "/b", "/c")),
    // cannot delete /withdeps in revert
    addGroup("/withdeps/some")
  ) {
      // expected outcome after revert of first deployment
      Group(
        PathId.empty,
        groups = Set(
          Group("othergroup1".toRootPath),
          Group("othergroup2".toRootPath),
          Group("othergroup3".toRootPath),
          {
            val id = "withdeps".toRootPath // withdeps still exists because of the subgroup
            Group(
              id,
              groups = Set(Group(id / "some")),
              dependencies = Set() // dependencies were introduce with first deployment, should be gone now
            )
          },
          {
            val id = "changeme".toRootPath
            Group(
              id,
              dependencies = Set(
                "othergroup1".toRootPath,
                "othergroup2".toRootPath
              ),
              apps = Set(
                AppDefinition(id / "app1"),
                AppDefinition(id / "app2")
              )
            )
          }
        )
      )
    }

  testWithConcurrentChange(original)(
    changeGroupDependencies("/changeme", remove = Seq("/othergroup1"), add = Seq("/othergroup3")),
    // "conflicting" dependency changes
    changeGroupDependencies("/changeme", remove = Seq("/othergroup2"), add = Seq("/othergroup4"))
  ) {
      // expected outcome after revert of first deployment
      Group(
        PathId.empty,
        groups = Set(
          Group("othergroup1".toRootPath),
          Group("othergroup2".toRootPath),
          Group("othergroup3".toRootPath),
          {
            val id = "changeme".toRootPath
            Group(
              id,
              dependencies = Set(
                // othergroup2 was removed and othergroup4 added
                "othergroup1".toRootPath,
                "othergroup4".toRootPath
              ),
              apps = Set(
                AppDefinition(id / "app1"),
                AppDefinition(id / "app2")
              )
            )
          }
        )
      )
    }

  testWithConcurrentChange(original)(
    removeGroup("/othergroup3"),
    // unrelated dependency changes
    changeGroupDependencies("/changeme", remove = Seq("/othergroup2"), add = Seq("/othergroup4"))
  ) {
      // expected outcome after revert of first deployment
      Group(
        PathId.empty,
        groups = Set(
          Group("othergroup1".toRootPath),
          Group("othergroup2".toRootPath),
          Group("othergroup3".toRootPath),
          {
            val id = "changeme".toRootPath
            Group(
              id,
              dependencies = Set(
                // othergroup2 was removed and othergroup4 added
                "othergroup1".toRootPath,
                "othergroup4".toRootPath
              ),
              apps = Set(
                AppDefinition(id / "app1"),
                AppDefinition(id / "app2")
              )
            )
          }
        )
      )
    }

  testWithConcurrentChange(
    original,
    addGroup("/changeme/some")
  )(
      // revert first
      addGroup("/changeme/some/a"),
      // concurrent deployments
      addGroup("/changeme/some/b")
    ) {
        // expected outcome after revert
        Group(
          PathId.empty,
          groups = Set(
            Group("othergroup1".toRootPath),
            Group("othergroup2".toRootPath),
            Group("othergroup3".toRootPath),
            {
              val id = "changeme".toRootPath
              Group(
                id,
                dependencies = Set(
                  "othergroup1".toRootPath,
                  "othergroup2".toRootPath
                ),
                apps = Set(
                  AppDefinition(id / "app1"),
                  AppDefinition(id / "app2")
                ),
                groups = Set(
                  Group(
                    id / "some",
                    groups = Set(
                      Group(id / "some" / "b")
                    )
                  )
                )
              )
            }
          )
        )
      }

  testWithConcurrentChange(
    original
  )(
      // revert first
      addApp("/changeme/some/a"),
      // concurrent deployments
      addApp("/changeme/some/b/a"),
      addApp("/changeme/some/b/b"),
      addApp("/changeme/some/b/c")
    ) {
        // expected outcome after revert
        Group(
          PathId.empty,
          groups = Set(
            Group("othergroup1".toRootPath),
            Group("othergroup2".toRootPath),
            Group("othergroup3".toRootPath),
            {
              val id = "changeme".toRootPath
              Group(
                id,
                dependencies = Set(
                  "othergroup1".toRootPath,
                  "othergroup2".toRootPath
                ),
                apps = Set(
                  AppDefinition(id / "app1"),
                  AppDefinition(id / "app2")
                ),
                groups = Set(
                  Group(
                    id / "some",
                    groups = Set(
                      Group(
                        id / "some" / "b",
                        apps = Set(
                          AppDefinition(id / "some" / "b" / "a"),
                          AppDefinition(id / "some" / "b" / "b"),
                          AppDefinition(id / "some" / "b" / "c")
                        )
                      )
                    )
                  )
                )
              )
            }
          )
        )
      }

  case class Deployment(name: String, change: Group => Group)

  private[this] def testWithConcurrentChange(originalBeforeChanges: Group, changesBeforeTest: Deployment*)(deployments: Deployment*)(expectedReverted: Group): Unit = {
    val firstDeployment = deployments.head

    def performDeployments(orig: Group, deployments: Seq[Deployment]): Group = {
      deployments.foldLeft(orig) {
        case (last: Group, deployment: Deployment) =>
          deployment.change(last)
      }
    }

    test(s"Reverting ${firstDeployment.name} after deploying ${deployments.tail.map(_.name).mkString(", ")}") {
      Given("an existing group with apps")
      val original = performDeployments(originalBeforeChanges, changesBeforeTest)

      When(s"performing a series of deployments (${deployments.map(_.name).mkString(", ")})")

      val targetWithAllDeployments = performDeployments(original, deployments)

      When(s"reverting the first one while we reset the versions before that")
      val newVersion = Timestamp(1)
      val deploymentReverterForFirst = DeploymentPlanReverter.revert(
        normalizeVersions(original),
        normalizeVersions(firstDeployment.change(original)),
        newVersion)
      val reverted = deploymentReverterForFirst(normalizeVersions(targetWithAllDeployments))

      Then("The result should only contain items with the prior or the new version")
      for (app <- reverted.transitiveApps) {
        withClue(s"version for app ${app.id} ") {
          app.version.toDateTime.getMillis should be <= (1L)
        }
      }

      for (group <- reverted.transitiveGroups) {
        withClue(s"version for group ${group.id} ") {
          group.version.toDateTime.getMillis should be <= (1L)
        }
      }

      Then("the result should be the same as if we had only applied all the other deployments")
      val targetWithoutFirstDeployment = performDeployments(original, deployments.tail)
      withClue("while comparing reverted with targetWithoutFirstDeployment: ") {
        assertEqualsExceptVersion(targetWithoutFirstDeployment, reverted)
      }

      Then("we should have the expected groups and apps")
      withClue("while comparing reverted with expected: ") {
        assertEqualsExceptVersion(expectedReverted, reverted)
      }
    }
  }

  private[this] def removeApp(appIdString: String) = {
    val appId = appIdString.toRootPath
    val parent = appId.parent
    Deployment(s"remove app '$appId'", _.update(parent, _.removeApplication(appId), Timestamp.now()))
  }
  private[this] def addApp(appId: String) = Deployment(s"add app '$appId'", _.updateApp(appId.toRootPath, _ => AppDefinition(appId.toRootPath), Timestamp.now()))
  private[this] def addGroup(groupId: String) = Deployment(s"add group '$groupId'", _.makeGroup(groupId.toRootPath))
  private[this] def removeGroup(groupId: String) = Deployment(s"remove group '$groupId'", _.remove(groupId.toRootPath))

  private[this] def changeGroupDependencies(groupId: String, add: Seq[String] = Seq.empty, remove: Seq[String] = Seq.empty) = {
    val addedIds = add.map(_.toRootPath)
    val removedIds = remove.map(_.toRootPath)

    def setDependencies(group: Group): Group = group.copy(dependencies = group.dependencies ++ addedIds -- removedIds)

    val name = if (removedIds.isEmpty)
      s"group '$groupId' add deps {${addedIds.mkString(", ")}}"
    else if (addedIds.isEmpty)
      s"group '$groupId' remove deps {${removedIds.mkString(", ")}}"
    else
      s"group '$groupId' change deps -{${removedIds.mkString(", ")}} +{${addedIds.mkString(", ")}}"

    Deployment(
      name,
      _.update(groupId.toRootPath, setDependencies, Timestamp.now()))
  }
}
