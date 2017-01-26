package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.{ AppInfo, GroupInfo }

class InfoEmbedResolverTest extends UnitTest {

  val prefixes = Seq("", "app.", "apps.", "group.apps.")

  "InfoEmbedResolver" should {
    for (prefix <- prefixes) {
      s"resolve ${prefix}lastTaskFailure" in {
        When(s"embed=${prefix}lastTaskFailure")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}lastTaskFailure"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.LastTaskFailure))
      }
    }

    for (prefix <- prefixes) {
      s"resolve ${prefix}counts" in {
        When(s"embed=${prefix}counts")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}counts"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.Counts))
      }
    }

    for (prefix <- prefixes) {
      s"resolve ${prefix}deployments" in {
        When(s"embed=${prefix}deployments")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}deployments"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.Deployments))
      }
    }

    for (prefix <- prefixes) {
      s"resolve ${prefix}readiness" in {
        When(s"embed=${prefix}readiness")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}readiness"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.Readiness))
      }
    }

    for (prefix <- prefixes) {
      s"resolve ${prefix}tasks" in {
        When(s"embed=${prefix}tasks")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}tasks"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments))
      }
    }

    for (prefix <- prefixes) {
      s"resolve ${prefix}failures" in {
        When(s"embed=${prefix}failures")
        val resolved = InfoEmbedResolver.resolveApp(Set(s"${prefix}failures"))
        Then("it should resolve correctly")
        resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments, AppInfo.Embed.LastTaskFailure))
      }
    }

    "Combining embed options works" in {
      When("embed=lastTaskFailure and embed=counts")
      val resolved = InfoEmbedResolver.resolveApp(Set("lastTaskFailure", "counts"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
    }

    "Unknown embed options are ignored" in {
      When("embed=lastTaskFailure and embed=counts and embed=something")
      val resolved = InfoEmbedResolver.resolveApp(Set("lastTaskFailure", "counts", "something"))
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
    }

    "Group resolving works" in {
      When("We resolve group embed infos")
      val resolved = InfoEmbedResolver.resolveGroup(Set("group.groups", "group.apps", "group.unknown", "unknown"))

      Then("The embed parameter are resolved correctly")
      resolved should be(Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))
    }

    "App / Group resolving works" in {
      When("We resolve group embed infos")
      val (app, group) = InfoEmbedResolver.resolveAppGroup(Set("group.groups", "group.apps", "group.apps.tasks", "group.apps.unknown", "group.unknown", "unknown"))

      Then("The embed parameter are resolved correctly")
      group should be(Set(GroupInfo.Embed.Apps, GroupInfo.Embed.Groups))
      app should be(Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments))
    }
  }
}
