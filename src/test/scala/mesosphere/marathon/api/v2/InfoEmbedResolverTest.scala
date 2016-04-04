package mesosphere.marathon.api.v2

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.appinfo.{ GroupInfo, AppInfo }
import org.scalatest.{ Matchers, GivenWhenThen }

class InfoEmbedResolverTest extends MarathonSpec with GivenWhenThen with Matchers {

  val prefixes = Seq("", "app.", "apps.", "group.apps.")

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}lastTaskFailure") {
      When(s"embed=${prefix}lastTaskFailure")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}lastTaskFailure"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.LastTaskFailure))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}counts") {
      When(s"embed=${prefix}counts")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}counts"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Counts))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}deployments") {
      When(s"embed=${prefix}deployments")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}deployments"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Deployments))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}readiness") {
      When(s"embed=${prefix}readiness")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}readiness"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Readiness))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}tasks") {
      When(s"embed=${prefix}tasks")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}tasks"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}failures") {
      When(s"embed=${prefix}failures")
      val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}failures"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments, AppInfo.Embed.LastTaskFailure))
    }
  }

  test("Combining embed options works") {
    When(s"embed=lastTaskFailure and embed=counts")
    val resolved = InfoEmbedResolver.resolveApp(Set("lastTaskFailure", "counts"))
    Then("it should resolve correctly")
    resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
  }

  test("Unknown embed options are ignored") {
    When(s"embed=lastTaskFailure and embed=counts and embed=something")
    val resolved = InfoEmbedResolver.resolveApp(Set("lastTaskFailure", "counts", "something"))
    Then("it should resolve correctly")
    resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
  }

  test("Group resolving works") {
    When("We resolve group embed infos")
    val resolved = InfoEmbedResolver.resolveGroup(Set("group.groups", "group.apps", "group.unknown", "unknown"))

    Then("The embed parameter are resolved correctly")
    resolved should be(Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))
  }

  test("App / Group resolving works") {
    When("We resolve group embed infos")
    val (app, group) = InfoEmbedResolver.resolveAppGroup(Set("group.groups", "group.apps", "group.apps.tasks", "group.apps.unknown", "group.unknown", "unknown"))

    Then("The embed parameter are resolved correctly")
    group should be(Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))
    app should be(Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments))
  }
}
