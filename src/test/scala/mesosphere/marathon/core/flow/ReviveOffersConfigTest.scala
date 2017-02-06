package mesosphere.marathon
package core.flow

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper

class ReviveOffersConfigTest extends UnitTest {
  "ReviveOffersConfig" should {
    "reviveOffersForNewApps is enabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      assert(conf.reviveOffersForNewApps())
    }

    "disable reviveOffersForNewApps" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--disable_revive_offers_for_new_apps"
      )
      assert(!conf.reviveOffersForNewApps())
    }
  }
}
