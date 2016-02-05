package mesosphere.marathon.core.flow

import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }

class ReviveOffersConfigTest extends MarathonSpec {
  test("reviveOffersForNewApps is enabled by default") {
    val conf = MarathonTestHelper.defaultConfig()
    assert(conf.reviveOffersForNewApps())
  }

  test("disable reviveOffersForNewApps") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--disable_revive_offers_for_new_apps"
    )
    assert(!conf.reviveOffersForNewApps())
  }
}
