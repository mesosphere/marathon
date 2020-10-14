package mesosphere.marathon
package core.appinfo.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.{AppInfo, GroupInfo, _}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.Future

class DefaultInfoServiceTest extends UnitTest with GroupCreation {
  "DefaultInfoService" should {
    "queryForAppId" in {
      Given("a group repo with some apps")
      val f = new Fixture
      f.groupManager.app(app1.id) returns Some(app1)
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying for one App")
      val appInfo = f.infoService.selectApp(id = app1.id, embed = Set.empty, selector = Selector.all).futureValue

      Then("we get an appInfo for the app from the appRepo/baseAppData")
      appInfo.map(_.id).toSet should be(Set(app1.id.toString))

      verify(f.groupManager, times(1)).app(app1.id)
      for (app <- Set(app1)) {
        verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
      }

      And("no more interactions")
      f.verifyNoMoreInteractions()
    }

    "queryForAppId passes embed options along" in {
      Given("a group repo with some apps")
      val f = new Fixture
      f.groupManager.app(app1.id) returns Some(app1)
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying for one App")
      val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
      f.infoService.selectApp(id = app1.id, embed = embed, selector = Selector.all).futureValue

      Then("we get the baseData calls with the correct embed info")
      for (app <- Set(app1)) {
        verify(f.baseData, times(1)).appInfoFuture(app, embed)
      }
    }

    "queryAll" in {
      Given("an app repo with some apps")
      val f = new Fixture
      val someGroup = createRootGroup(apps = someApps)
      f.groupManager.rootGroup() returns someGroup
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying all apps")
      val appInfos = f.infoService.selectAppsBy(Selector.all, embed = Set.empty).futureValue

      Then("we get appInfos for each app from the appRepo/baseAppData")
      appInfos.map(_.id).toSet should be(someApps.keys.map(_.toString))

      verify(f.groupManager, times(1)).rootGroup()
      for (app <- someApps.values) {
        verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
      }

      And("no more interactions")
      f.verifyNoMoreInteractions()
    }

    "queryAll passes embed options along" in {
      Given("an app repo with some apps")
      val f = new Fixture
      val someGroup = createRootGroup(apps = someApps)
      f.groupManager.rootGroup() returns someGroup
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying all apps")
      val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
      f.infoService.selectAppsBy(Selector.all, embed = embed).futureValue

      Then("we get the base data calls with the correct embed")
      for (app <- someApps.values) {
        verify(f.baseData, times(1)).appInfoFuture(app, embed)
      }
    }

    "queryAll filters" in {
      Given("an app repo with some apps")
      val f = new Fixture
      val someGroup = createRootGroup(apps = someApps)
      f.groupManager.rootGroup() returns someGroup

      When("querying all apps with a filter that filters all apps")
      val appInfos = f.infoService.selectAppsBy(Selector.none, embed = Set.empty).futureValue

      Then("we get appInfos for no app from the appRepo/baseAppData")
      appInfos.map(_.id).toSet should be(Set.empty)

      verify(f.groupManager, times(1)).rootGroup()

      And("no more interactions")
      f.verifyNoMoreInteractions()
    }

    "queryForGroupId" in {
      Given("a group repo with some apps below the queried group id")
      val f = new Fixture
      f.groupManager.group(AbsolutePathId("/nested")) returns someGroupWithNested.group(AbsolutePathId("/nested"))
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying all apps in that group")
      val appInfos = f.infoService.selectAppsInGroup(AbsolutePathId("/nested"), Selector.all, Set.empty).futureValue

      Then("we get appInfos for each app from the groupRepo/baseAppData")
      appInfos.map(_.id).toSet should be(someNestedApps.keys.map(_.toString))

      verify(f.groupManager, times(1)).group(AbsolutePathId("/nested"))
      for (app <- someNestedApps.values) {
        verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
      }

      And("no more interactions")
      f.verifyNoMoreInteractions()
    }

    "queryForGroupId passes embed infos along" in {
      Given("a group repo with some apps below the queried group id")
      val f = new Fixture
      f.groupManager.group(AbsolutePathId("/nested")) returns someGroupWithNested.group(AbsolutePathId("/nested"))
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }

      When("querying all apps in that group")
      val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
      f.infoService.selectAppsInGroup(AbsolutePathId("/nested"), Selector.all, embed).futureValue

      Then("baseData was called with the correct embed options")
      for (app <- someNestedApps.values) {
        verify(f.baseData, times(1)).appInfoFuture(app, embed)
      }
    }

    "query for extended group information" in {
      Given("a group with apps")
      val f = new Fixture
      val rootGroup = someGroupWithNested
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }
      f.groupManager.group(rootGroup.id) returns Some(rootGroup)

      When("querying extending group information")
      val result = f.infoService.selectGroup(
        rootGroup.id,
        GroupInfoService.Selectors.all,
        Set.empty,
        Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups)
      )

      Then("The group info contains apps and groups")
      result.futureValue.value.apps should have size 3
      result.futureValue.value.groups should have size 1
      result.futureValue.value.groups.head.apps should have size 2

      When("querying extending group information without apps")
      val result2 = f.infoService.selectGroup(rootGroup.id, GroupInfoService.Selectors.all, Set.empty, Set(GroupInfo.Embed.Groups))

      Then("The group info contains no apps but groups")
      result2.futureValue.value.groups should be('nonEmpty)
      result2.futureValue.value.apps should be(empty)

      When("querying extending group information without apps and groups")
      val result3 = f.infoService.selectGroup(rootGroup.id, GroupInfoService.Selectors.all, Set.empty, Set.empty)

      Then("The group info contains no apps nor groups")
      result3.futureValue.value.groups should be(empty)
      result3.futureValue.value.apps should be(empty)
    }

    "Selecting with Group Selector filters the result" in {
      Given("a nested group with apps")
      val f = new Fixture
      val rootGroup = nestedGroup
      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }
      f.groupManager.group(rootGroup.id) returns Some(rootGroup)
      val selector = GroupInfoService.Selectors(
        Selector(_.id.toString.startsWith("/visible")),
        Selector(_.id.toString.startsWith("/visible")),
        Selector(_.id.toString.startsWith("/visible"))
      )

      When("querying extending group information with selector")
      val result = f.infoService.selectGroup(rootGroup.id, selector, Set.empty, Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))

      Then("The result is filtered by the selector")
      val res = result.futureValue.value
      res.groups should have size 1
      res.groups.head.apps should have size 1
      res.groups.head.groups should have size 1
      res.groups.head.apps should have size 1
    }

    "Selecting with App Selector implicitly gives access to parent groups" in {
      Given("a nested group with access to only nested app /group/app1")
      val f = new Fixture
      val rootId = PathId.root
      val rootApp = AppDefinition(AbsolutePathId("/app"), cmd = Some("sleep"), role = "*")
      val nestedApp1 = AppDefinition(AbsolutePathId("/group/app1"), cmd = Some("sleep"), role = "*")
      val nestedApp2 = AppDefinition(AbsolutePathId("/group/app2"), cmd = Some("sleep"), role = "*")
      val nestedGroup = createGroup(AbsolutePathId("/group"), Map(nestedApp1.id -> nestedApp1, nestedApp2.id -> nestedApp2))
      val rootGroup = createRootGroup(Map(rootApp.id -> rootApp), groups = Set(nestedGroup))

      f.baseData.appInfoFuture(any, any) answers { args =>
        Future.successful(raml.AppInfo.fromParent(parent = Raml.toRaml(args.head.asInstanceOf[AppDefinition])))
      }
      f.groupManager.group(rootId) returns Some(rootGroup)
      val selector = GroupInfoService.Selectors(
        Selector(_.id.toString.startsWith("/group/app1")),
        Selector(_ => false), // no pod
        Selector(_ => false) // no group
      )

      When("querying extending group information with selector")
      val result = f.infoService.selectGroup(rootId, selector, Set.empty, Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))

      Then("The result is filtered by the selector")
      result.futureValue.value.groups should have size 1

      val apps: Set[AppDefinition] = Set(nestedApp1)
      val expectedApps = apps.map(app => raml.AppInfo.fromParent(Raml.toRaml(app)))

      val expectedGroupInfo = raml.GroupInfo(
        id = nestedGroup.id.toString,
        apps = expectedApps,
        pods = Set(),
        groups = Set(),
        version = Some(nestedGroup.version.toOffsetDateTime),
        enforceRole = Some(nestedGroup.enforceRole)
      )

      result.futureValue.value.groups should have size 1
      result.futureValue.value.groups.head should be(expectedGroupInfo)
      result.futureValue.value.groups.head.apps should have size 1
    }
  }

  class Fixture {
    lazy val groupManager = mock[GroupManager]
    lazy val baseData = mock[AppInfoBaseData]

    import scala.concurrent.ExecutionContext.Implicits.global
    lazy val infoService = new DefaultInfoService(groupManager, { () => baseData })

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(groupManager)
      noMoreInteractions(baseData)
    }
  }

  private val app1: AppDefinition = AppDefinition(AbsolutePathId("/test1"), cmd = Some("sleep"), role = "*")
  val someApps = {
    val app2 = AppDefinition(AbsolutePathId("/test2"), cmd = Some("sleep"), role = "*")
    val app3 = AppDefinition(AbsolutePathId("/test3"), cmd = Some("sleep"), role = "*")
    Map(
      app1.id -> app1,
      app2.id -> app2,
      app3.id -> app3
    )
  }

  val someNestedApps = {
    val nestedApp1 = AppDefinition(AbsolutePathId("/nested/test1"), cmd = Some("sleep"), role = "*")
    val nestedApp2 = AppDefinition(AbsolutePathId("/nested/test2"), cmd = Some("sleep"), role = "*")
    Map(
      (nestedApp1.id, nestedApp1),
      (nestedApp2.id, nestedApp2)
    )
  }

  val someGroupWithNested = createRootGroup(
    apps = someApps,
    groups = Set(
      createGroup(
        id = AbsolutePathId("/nested"),
        apps = someNestedApps
      )
    )
  )

  val nestedGroup = {
    val app1 = AppDefinition(AbsolutePathId("/app1"), cmd = Some("sleep"), role = "*")
    val visibleApp1 = AppDefinition(AbsolutePathId("/visible/app1"), cmd = Some("sleep"), role = "*")
    val visibleGroupApp1 = AppDefinition(AbsolutePathId("/visible/group/app1"), cmd = Some("sleep"), role = "*")
    val secureApp1 = AppDefinition(AbsolutePathId("/secure/app1"), cmd = Some("sleep"), role = "*")
    val secureGroupApp1 = AppDefinition(AbsolutePathId("/secure/group/app1"), cmd = Some("sleep"), role = "*")
    val otherApp1 = AppDefinition(AbsolutePathId("/other/app1"), cmd = Some("sleep"), role = "*")
    val otherGroupApp1 = AppDefinition(AbsolutePathId("/other/group/app1"), cmd = Some("sleep"), role = "*")

    createRootGroup(
      Map(app1.id -> app1),
      groups = Set(
        createGroup(
          AbsolutePathId("/visible"),
          Map(visibleApp1.id -> visibleApp1),
          groups = Set(
            createGroup(AbsolutePathId("/visible/group"), Map(visibleGroupApp1.id -> visibleGroupApp1))
          )
        ),
        createGroup(
          AbsolutePathId("/secure"),
          Map(secureApp1.id -> secureApp1),
          groups = Set(
            createGroup(AbsolutePathId("/secure/group"), Map(secureGroupApp1.id -> secureGroupApp1))
          )
        ),
        createGroup(
          AbsolutePathId("/other"),
          Map(otherApp1.id -> otherApp1),
          groups = Set(createGroup(AbsolutePathId("/other/group"), Map(otherGroupApp1.id -> otherGroupApp1)))
        )
      )
    )
  }
}
