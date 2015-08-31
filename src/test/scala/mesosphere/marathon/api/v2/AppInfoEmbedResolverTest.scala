package mesosphere.marathon.api.v2

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.appinfo.AppInfo
import org.scalatest.{ Matchers, GivenWhenThen }

class AppInfoEmbedResolverTest extends MarathonSpec with GivenWhenThen with Matchers {
  import scala.collection.JavaConverters._

  val prefixes = Seq("", "app.", "apps.")

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}lastTaskFailure") {
      When(s"embed=${prefix}lastTaskFailure")
      val resolved = AppInfoEmbedResolver.resolve(Set(s"${prefix}lastTaskFailure").asJava)
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.LastTaskFailure))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}counts") {
      When(s"embed=${prefix}counts")
      val resolved = AppInfoEmbedResolver.resolve(Set(s"${prefix}counts").asJava)
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Counts))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}deployments") {
      When(s"embed=${prefix}deployments")
      val resolved = AppInfoEmbedResolver.resolve(Set(s"${prefix}deployments").asJava)
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Deployments))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}tasks") {
      When(s"embed=${prefix}tasks")
      val resolved = AppInfoEmbedResolver.resolve(Set(s"${prefix}tasks").asJava)
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments))
    }
  }

  for (prefix <- prefixes) {
    test(s"resolve ${prefix}failures") {
      When(s"embed=${prefix}failures")
      val resolved = AppInfoEmbedResolver.resolve(Set(s"${prefix}failures").asJava)
      Then("it should resolve correctly")
      resolved should be (Set(AppInfo.Embed.Tasks, AppInfo.Embed.Deployments, AppInfo.Embed.LastTaskFailure))
    }
  }

  test(s"Combining embed options works") {
    When(s"embed=lastTaskFailure and embed=counts")
    val resolved = AppInfoEmbedResolver.resolve(Set("lastTaskFailure", "counts").asJava)
    Then("it should resolve correctly")
    resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
  }

  test(s"Unknown embed options are ignored") {
    When(s"embed=lastTaskFailure and embed=counts and embed=something")
    val resolved = AppInfoEmbedResolver.resolve(Set("lastTaskFailure", "counts", "something").asJava)
    Then("it should resolve correctly")
    resolved should be (Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Counts))
  }

}
