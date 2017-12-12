package mesosphere.marathon
package core.deployment.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation

class DeploymentPlanRevertTest extends UnitTest with GroupCreation {
  case class Deployment(name: String, change: RootGroup => RootGroup)

  /**
    * An assert equals which provides better feedback about what's different for groups.
    */
  private def assertEqualsExceptVersion(expectedOrig: RootGroup, actualOrig: RootGroup): Unit = {
    val expected: RootGroup = expectedOrig.withNormalizedVersions
    val actual: RootGroup = actualOrig.withNormalizedVersions

    if (expected != actual) {
      val actualGroupIds = actual.transitiveGroupsById.keySet
      val expectedGroupIds = expected.transitiveGroupsById.keySet

      val unexpectedGroupIds = actualGroupIds -- expectedGroupIds
      val missingGroupIds = expectedGroupIds -- actualGroupIds

      withClue(s"unexpected groups $unexpectedGroupIds, missing groups $missingGroupIds: ") {
        actualGroupIds should equal(expectedGroupIds)
      }

      for (groupId <- expectedGroupIds) {
        withClue(s"for group id $groupId") {
          actual.group(groupId) should equal(expected.group(groupId))
        }
      }

      val actualAppIds = actual.transitiveAppIds
      val expectedAppIds = expected.transitiveAppIds

      val unexpectedAppIds = actualAppIds.filter(appId => expected.app(appId).isEmpty)
      val missingAppIds = expectedAppIds.filter(appId => actual.app(appId).isEmpty)

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
  private[this] def removeApp(appId: String) = Deployment(s"remove app '$appId'", _.removeApp(appId.toRootPath))
  private[this] def addApp(appId: String) = Deployment(s"add app '$appId'", _.updateApp(appId.toRootPath, _ => AppDefinition(appId.toRootPath, cmd = Some("sleep")), Timestamp.now()))
  private[this] def addGroup(groupId: String) = Deployment(s"add group '$groupId'", _.makeGroup(groupId.toRootPath))
  private[this] def removeGroup(groupId: String) = Deployment(s"remove group '$groupId'", _.removeGroup(groupId.toRootPath))

  private[this] def testWithConcurrentChange(originalBeforeChanges: RootGroup, changesBeforeTest: Deployment*)(deployments: Deployment*)(expectedReverted: RootGroup): Unit = {
    val firstDeployment = deployments.head

    def performDeployments(orig: RootGroup, deployments: Seq[Deployment]): RootGroup = {
      deployments.foldLeft(orig) {
        case (last: RootGroup, deployment: Deployment) =>
          deployment.change(last)
      }
    }

    s"Reverting ${firstDeployment.name} after deploying ${deployments.tail.map(_.name).mkString(", ")}" in {
      Given("an existing group with apps")
      val original = performDeployments(originalBeforeChanges, changesBeforeTest.to[Seq])

      When(s"performing a series of deployments (${deployments.map(_.name).mkString(", ")})")

      val targetWithAllDeployments = performDeployments(original, deployments.to[Seq])

      When("reverting the first one while we reset the versions before that")
      val newVersion = Timestamp(1)
      val deploymentReverterForFirst = DeploymentPlanReverter.revert(
        original.withNormalizedVersions,
        firstDeployment.change(original).withNormalizedVersions,
        newVersion)
      val reverted = deploymentReverterForFirst(targetWithAllDeployments.withNormalizedVersions)

      Then("The result should only contain items with the prior or the new version")
      for (app <- reverted.transitiveApps) {
        withClue(s"version for app ${app.id} ") {
          app.version.millis should be <= 1L
        }
      }

      for (group <- reverted.transitiveGroupsById.values) {
        withClue(s"version for group ${group.id} ") {
          group.version.millis should be <= 1L
        }
      }

      Then("the result should be the same as if we had only applied all the other deployments")
      val targetWithoutFirstDeployment = performDeployments(original, deployments.tail.to[Seq])
      withClue("while comparing reverted with targetWithoutFirstDeployment: ") {
        assertEqualsExceptVersion(targetWithoutFirstDeployment, reverted)
      }

      Then("we should have the expected groups and apps")
      withClue("while comparing reverted with expected: ") {
        assertEqualsExceptVersion(expectedReverted, reverted)
      }
    }
  }

  private[this] def changeGroupDependencies(groupId: String, add: Seq[String] = Seq.empty, remove: Seq[String] = Seq.empty) = {
    val addedIds = add.map(_.toRootPath)
    val removedIds = remove.map(_.toRootPath)

    val name = if (removedIds.isEmpty)
      s"group '$groupId' add deps {${addedIds.mkString(", ")}}"
    else if (addedIds.isEmpty)
      s"group '$groupId' remove deps {${removedIds.mkString(", ")}}"
    else
      s"group '$groupId' change deps -{${removedIds.mkString(", ")}} +{${addedIds.mkString(", ")}}"

    Deployment(name, _.updateDependencies(groupId.toRootPath, _ ++ addedIds -- removedIds, Timestamp.now()))
  }

  "RevertDeploymentPlan" should {
    "Revert app addition" in {
      Given("an unrelated group")
      val unrelatedGroup = {
        val id = "unrelated".toRootPath
        val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
        val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
        createGroup(
          id,
          apps = Map(
            app1.id -> app1,
            app2.id -> app2
          )
        )
      }

      val original = createRootGroup(groups = Set(unrelatedGroup))

      When("we add an unrelated app and try to revert that without concurrent changes")
      val target = RootGroup.fromGroup(original.updateApp("test".toRootPath, _ => AppDefinition("test".toRootPath), Timestamp.now()))
      val plan = DeploymentPlan(original, target)
      val revertToOriginal = plan.revert(target)

      Then("we get back the original definitions")
      assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
    }

    "Revert app removal" in {
      Given("an existing group with apps")
      val changeme = {
        val id = "changeme".toRootPath
        val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
        val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
        createGroup(
          id,
          apps = Map(
            app1.id -> app1,
            app2.id -> app2
          )
        )
      }

      val original = createRootGroup(groups = Set(changeme))

      When("we remove an app and try to revert that without concurrent changes")
      val appId = "/changeme/app1".toRootPath
      val target = original.removeApp(appId)
      target.app(appId) should be('empty)
      val plan = DeploymentPlan(original, target)
      val revertToOriginal = plan.revert(target)

      Then("we get back the original definitions")
      assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
    }

    "Revert removing a group without apps" in {
      Given("a group")
      val original = createRootGroup(groups = Set(createGroup("changeme".toRootPath)))

      When("we remove the group and try to revert that without concurrent changes")
      val target = original.removeGroup("changeme".toRootPath)
      val plan = DeploymentPlan(original, target)
      val revertToOriginal = plan.revert(target)

      Then("we get back the original definitions")
      assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
    }

    "Revert removing a group with apps" in {
      Given("a group")
      val changeme = {
        val id = "changeme".toRootPath
        val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
        val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
        createGroup(
          id,
          apps = Map(
            app1.id -> app1,
            app2.id -> app2
          )
        )
      }

      val original = createRootGroup(groups = Set(changeme))

      When("we remove the group and try to revert that without concurrent changes")
      val target = original.removeGroup("changeme".toRootPath)
      val plan = DeploymentPlan(original, target)
      val revertToOriginal = plan.revert(target)

      Then("we get back the original definitions")
      assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
    }

    "Revert group dependency changes" in {
      Given("an existing group with apps")
      val existingGroup = {
        val id = "changeme".toRootPath
        val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
        val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
        createGroup(
          id,
          dependencies = Set(
            "othergroup1".toRootPath,
            "othergroup2".toRootPath
          ),
          apps = Map(
            app1.id -> app1,
            app2.id -> app2
          )
        )
      }

      val original = createRootGroup(
        groups = Set(
          createGroup("othergroup1".toRootPath),
          createGroup("othergroup2".toRootPath),
          createGroup("othergroup3".toRootPath),
          existingGroup
        )
      )

      When("we change the dependencies to the existing group")
      val target = original.updateDependencies(
        existingGroup.id,
        _ => Set("othergroup2".toRootPath, "othergroup3".toRootPath),
        original.version)
      val plan = DeploymentPlan(original, target)
      val revertToOriginal = plan.revert(target)

      Then("we get back the original definitions")
      assertEqualsExceptVersion(original, actualOrig = revertToOriginal)
    }

    val existingGroup = {
      val id = "changeme".toRootPath
      val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
      val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
      createGroup(
        id,
        dependencies = Set(
          "othergroup1".toRootPath,
          "othergroup2".toRootPath
        ),
        apps = Map(
          app1.id -> app1,
          app2.id -> app2
        )
      )
    }

    val original = createRootGroup(
      groups = Set(
        createGroup("othergroup1".toRootPath),
        createGroup("othergroup2".toRootPath),
        createGroup("othergroup3".toRootPath),
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
        createRootGroup(
          groups = Set(
            createGroup("othergroup1".toRootPath),
            createGroup("othergroup2".toRootPath),
            createGroup("othergroup3".toRootPath),
            {
              val id = "other".toRootPath
              val app4 = AppDefinition(id / "app4", cmd = Some("sleep"))
              createGroup(
                id,
                apps = Map(app4.id -> app4) // app4 was added
              )
            },
            {
              val id = "changeme".toRootPath
              val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
              val app3 = AppDefinition(id / "app3", cmd = Some("sleep"))
              createGroup(
                id,
                dependencies = Set(
                  "othergroup1".toRootPath,
                  "othergroup2".toRootPath
                ),
                apps = Map(
                  app1.id -> app1, // app1 was kept
                  // app2 was removed
                  app3.id -> app3 // app3 was added
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
        createRootGroup(
          groups = Set(
            createGroup("othergroup1".toRootPath),
            createGroup("othergroup2".toRootPath),
            createGroup("othergroup3".toRootPath),
            {
              val id = "withdeps".toRootPath // withdeps still exists because of the subgroup
              createGroup(
                id,
                apps = Group.defaultApps,
                groups = Set(createGroup(id / "some")),
                dependencies = Set() // dependencies were introduce with first deployment, should be gone now
              )
            },
            {
              val id = "changeme".toRootPath
              val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
              val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
              createGroup(
                id,
                dependencies = Set(
                  "othergroup1".toRootPath,
                  "othergroup2".toRootPath
                ),
                apps = Map(
                  app1.id -> app1,
                  app2.id -> app2
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
        createRootGroup(
          groups = Set(
            createGroup("othergroup1".toRootPath),
            createGroup("othergroup2".toRootPath),
            createGroup("othergroup3".toRootPath),
            {
              val id = "changeme".toRootPath
              val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
              val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
              createGroup(
                id,
                dependencies = Set(
                  // othergroup2 was removed and othergroup4 added
                  "othergroup1".toRootPath,
                  "othergroup4".toRootPath
                ),
                apps = Map(
                  app1.id -> app1,
                  app2.id -> app2
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
        createRootGroup(
          groups = Set(
            createGroup("othergroup1".toRootPath),
            createGroup("othergroup2".toRootPath),
            createGroup("othergroup3".toRootPath),
            {
              val id = "changeme".toRootPath
              val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
              val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
              createGroup(
                id,
                dependencies = Set(
                  // othergroup2 was removed and othergroup4 added
                  "othergroup1".toRootPath,
                  "othergroup4".toRootPath
                ),
                apps = Map(
                  app1.id -> app1,
                  app2.id -> app2
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
          createRootGroup(
            groups = Set(
              createGroup("othergroup1".toRootPath),
              createGroup("othergroup2".toRootPath),
              createGroup("othergroup3".toRootPath),
              {
                val id = "changeme".toRootPath
                val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
                val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
                createGroup(
                  id,
                  dependencies = Set(
                    "othergroup1".toRootPath,
                    "othergroup2".toRootPath
                  ),
                  apps = Map(
                    app1.id -> app1,
                    app2.id -> app2
                  ),
                  groups = Set(
                    createGroup(
                      id / "some",
                      groups = Set(
                        createGroup(id / "some" / "b")
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
        createRootGroup(
          groups = Set(
            createGroup("othergroup1".toRootPath),
            createGroup("othergroup2".toRootPath),
            createGroup("othergroup3".toRootPath),
            {
              val id = "changeme".toRootPath
              val app1 = AppDefinition(id / "app1", cmd = Some("sleep"))
              val app2 = AppDefinition(id / "app2", cmd = Some("sleep"))
              val appBA = AppDefinition(id / "some" / "b" / "a", cmd = Some("sleep"))
              val appBB = AppDefinition(id / "some" / "b" / "b", cmd = Some("sleep"))
              val appBC = AppDefinition(id / "some" / "b" / "c", cmd = Some("sleep"))
              createGroup(
                id,
                dependencies = Set(
                  "othergroup1".toRootPath,
                  "othergroup2".toRootPath
                ),
                apps = Map(
                  app1.id -> app1,
                  app2.id -> app2
                ),
                groups = Set(
                  createGroup(
                    id / "some",
                    groups = Set(
                      createGroup(
                        id / "some" / "b",
                        apps = Map(
                          appBA.id -> appBA,
                          appBB.id -> appBB,
                          appBC.id -> appBC
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

  }
}
