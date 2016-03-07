package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.appinfo.{ GroupSelector, GroupInfo, AppInfo, AppSelector }
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }

import scala.concurrent.Future

class DefaultInfoServiceTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {
  import mesosphere.FutureTestSupport._

  test("queryForAppId") {
    Given("a group repo with some apps")
    val f = new Fixture
    f.appRepo.currentVersion(app1.id) returns Future.successful(Some(app1))
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying for one App")
    val appInfo = f.infoService.selectApp(id = app1.id, embed = Set.empty, selector = AppSelector.all).futureValue

    Then("we get an appInfo for the app from the appRepo/baseAppData")
    appInfo.map(_.app.id).toSet should be(Set(app1.id))

    verify(f.appRepo, times(1)).currentVersion(app1.id)
    for (app <- Set(app1)) {
      verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
    }

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("queryForAppId passes embed options along") {
    Given("a group repo with some apps")
    val f = new Fixture
    f.appRepo.currentVersion(app1.id) returns Future.successful(Some(app1))
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying for one App")
    val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
    f.infoService.selectApp(id = app1.id, embed = embed, selector = AppSelector.all).futureValue

    Then("we get the baseData calls with the correct embed info")
    for (app <- Set(app1)) {
      verify(f.baseData, times(1)).appInfoFuture(app, embed)
    }
  }

  test("queryAll") {
    Given("an app repo with some apps")
    val f = new Fixture
    val someGroup = Group.empty.copy(apps = someApps)
    f.groupManager.rootGroup() returns Future.successful(someGroup)
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying all apps")
    val appInfos = f.infoService.selectAppsBy(AppSelector(_ => true), embed = Set.empty).futureValue

    Then("we get appInfos for each app from the appRepo/baseAppData")
    appInfos.map(_.app.id).toSet should be(someApps.map(_.id))

    verify(f.groupManager, times(1)).rootGroup()
    for (app <- someApps) {
      verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
    }

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("queryAll passes embed options along") {
    Given("an app repo with some apps")
    val f = new Fixture
    val someGroup = Group.empty.copy(apps = someApps)
    f.groupManager.rootGroup() returns Future.successful(someGroup)
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying all apps")
    val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
    f.infoService.selectAppsBy(AppSelector(_ => true), embed = embed).futureValue

    Then("we get the base data calls with the correct embed")
    for (app <- someApps) {
      verify(f.baseData, times(1)).appInfoFuture(app, embed)
    }
  }

  test("queryAll filters") {
    Given("an app repo with some apps")
    val f = new Fixture
    val someGroup = Group.empty.copy(apps = someApps)
    f.groupManager.rootGroup() returns Future.successful(someGroup)

    When("querying all apps with a filter that filters all apps")
    val appInfos = f.infoService.selectAppsBy(AppSelector(_ => false), embed = Set.empty).futureValue

    Then("we get appInfos for no app from the appRepo/baseAppData")
    appInfos.map(_.app.id).toSet should be(Set.empty)

    verify(f.groupManager, times(1)).rootGroup()

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("queryForGroupId") {
    Given("a group repo with some apps below the queried group id")
    val f = new Fixture
    f.groupManager.group(PathId("/nested")) returns Future.successful(someGroupWithNested.group(PathId("/nested")))
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying all apps in that group")
    val appInfos = f.infoService.selectAppsInGroup(PathId("/nested"), AppSelector.all, Set.empty).futureValue

    Then("we get appInfos for each app from the groupRepo/baseAppData")
    appInfos.map(_.app.id).toSet should be(someNestedApps.map(_.id))

    verify(f.groupManager, times(1)).group(PathId("/nested"))
    for (app <- someNestedApps) {
      verify(f.baseData, times(1)).appInfoFuture(app, Set.empty)
    }

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("queryForGroupId passes embed infos along") {
    Given("a group repo with some apps below the queried group id")
    val f = new Fixture
    f.groupManager.group(PathId("/nested")) returns Future.successful(someGroupWithNested.group(PathId("/nested")))
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }

    When("querying all apps in that group")
    val embed: Set[AppInfo.Embed] = Set(AppInfo.Embed.Tasks, AppInfo.Embed.Counts)
    f.infoService.selectAppsInGroup(PathId("/nested"), AppSelector.all, embed).futureValue

    Then("baseData was called with the correct embed options")
    for (app <- someNestedApps) {
      verify(f.baseData, times(1)).appInfoFuture(app, embed)
    }
  }

  test("query for extended group information") {
    Given("a group with apps")
    val f = new Fixture
    val group = someGroupWithNested
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }
    f.groupManager.group(group.id) returns Future.successful(Some(group))

    When("querying extending group information")
    val result = f.infoService.selectGroup(group.id, GroupSelector.all, Set.empty, Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))

    Then("The group info contains apps and groups")
    result.futureValue.get.maybeGroups should be(defined)
    result.futureValue.get.maybeApps should be(defined)
    result.futureValue.get.transitiveApps.get should have size 5
    result.futureValue.get.maybeGroups.get should have size 1

    When("querying extending group information without apps")
    val result2 = f.infoService.selectGroup(group.id, GroupSelector.all, Set.empty, Set(GroupInfo.Embed.Groups))

    Then("The group info contains no apps but groups")
    result2.futureValue.get.maybeGroups should be(defined)
    result2.futureValue.get.maybeApps should be(empty)

    When("querying extending group information without apps and groups")
    val result3 = f.infoService.selectGroup(group.id, GroupSelector.all, Set.empty, Set.empty)

    Then("The group info contains no apps nor groups")
    result3.futureValue.get.maybeGroups should be(empty)
    result3.futureValue.get.maybeApps should be(empty)
  }

  test("Selecting with Group Selector filters the result") {
    Given("a nested group with apps")
    val f = new Fixture
    val group = nestedGroup
    f.baseData.appInfoFuture(any, any) answers { args =>
      Future.successful(AppInfo(args.head.asInstanceOf[AppDefinition]))
    }
    f.groupManager.group(group.id) returns Future.successful(Some(group))
    val selector = new GroupSelector {
      override def matches(group: Group): Boolean = group.id.toString.startsWith("/visible")
      override def matches(app: AppDefinition): Boolean = app.id.toString.startsWith("/visible")
    }

    When("querying extending group information with selector")
    val result = f.infoService.selectGroup(group.id, selector, Set.empty, Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))

    Then("The result is filtered by the selector")
    result.futureValue.get.maybeGroups should be(defined)
    result.futureValue.get.maybeApps should be(defined)
    result.futureValue.get.transitiveApps.get should have size 2
    result.futureValue.get.transitiveGroups.get should have size 2
  }

  class Fixture {
    lazy val groupManager = mock[GroupManager]
    lazy val appRepo = mock[AppRepository]
    lazy val baseData = mock[AppInfoBaseData]
    def newBaseData(): AppInfoBaseData = baseData
    lazy val infoService = new DefaultInfoService(groupManager, appRepo, newBaseData)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(groupManager)
      noMoreInteractions(appRepo)
      noMoreInteractions(baseData)
    }
  }

  private val app1: AppDefinition = AppDefinition(PathId("/test1"))
  val someApps = Set(
    app1,
    AppDefinition(PathId("/test2")),
    AppDefinition(PathId("/test3"))
  )

  val someNestedApps = Set(
    AppDefinition(PathId("/nested/test1")),
    AppDefinition(PathId("/nested/test2"))
  )

  val someGroupWithNested = Group.empty.copy(
    apps = someApps,
    groups = Set(
      Group.empty.copy(
        id = PathId("/nested"),
        apps = someNestedApps
      )
    )
  )

  val nestedGroup = Group(PathId.empty, Set(AppDefinition(PathId("/app1"))), Set(
    Group(PathId("/visible"), Set(AppDefinition(PathId("/visible/app1"))), Set(
      Group(PathId("/visible/group"), Set(AppDefinition(PathId("/visible/group/app1"))))
    )),
    Group(PathId("/secure"), Set(AppDefinition(PathId("/secure/app1"))), Set(
      Group(PathId("/secure/group"), Set(AppDefinition(PathId("/secure/group/app1"))))
    )),
    Group(PathId("/other"), Set(AppDefinition(PathId("/other/app1"))), Set(
      Group(PathId("/other/group"), Set(AppDefinition(PathId("/other/group/app1")))
      ))
    )))
}
