package mesosphere.util

import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.Matchers

class ConfigToMapTest extends MarathonSpec with Matchers {

  test("should convert config into map") {
    val config = MarathonTestHelper.defaultConfig()

    val configMap = ConfigToMap.convertToMap(config)

    configMap.size should equal(45)
    configMap("master").get should equal("127.0.0.1:5050")
    configMap("max_tasks_per_offer").get should equal(1)
    configMap("min_revive_offers_interval").get should equal(100)
    configMap("webui_url") should equal(None)
  }

}
