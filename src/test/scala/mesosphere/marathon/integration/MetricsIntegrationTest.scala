package mesosphere.marathon
package integration

import akka.http.scaladsl.model.HttpResponse
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{ EmbeddedMarathonTest, RestResult }
import mesosphere.marathon.raml.GroupUpdate
import play.api.libs.json.{ JsObject, Json }

@IntegrationTest
class MetricsIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  "Marathon Metrics" should {
    "correctly count outgoing HTTP bytes" in {

      When("The metrics endpoint is queried")
      val result = marathon.metrics()

      Then("The system responds as expected")
      result should be(OK)
      result.entityJson.as[JsObject].keys should contain("counters")
      result.entityJson.as[JsObject].value("counters").as[JsObject].keys should contain("service.mesosphere.marathon.api.HTTPMetricsFilter.bytesWritten")

      And("The `outputBytes` is increased as expected")
      def getCounter(result: RestResult[HttpResponse]): Int = {
        result.entityJson.as[JsObject].value("counters").as[JsObject].value(
          "service.mesosphere.marathon.api.HTTPMetricsFilter.bytesWritten").as[JsObject].value("count").as[Int]
      }
      val currentCounter = getCounter(result)

      // Give some time to Kamon to take a metrics snapshot.
      Thread.sleep(3000)

      val newResult = marathon.metrics()
      val newCounter = getCounter(newResult)
      newCounter shouldBe >=(currentCounter + result.entityString.length)
    }

    "correctly count incoming HTTP bytes" in {

      When("The metrics endpoint is queried")
      val result = marathon.metrics()

      Then("The system responds as expected")
      result should be(OK)
      result.entityJson.as[JsObject].keys should contain("counters")
      result.entityJson.as[JsObject].value("counters").as[JsObject].keys should contain("service.mesosphere.marathon.api.HTTPMetricsFilter.bytesRead")

      And("The `inputBytes` is increased as expected")
      def getCounter(result: RestResult[HttpResponse]): Int = {
        result.entityJson.as[JsObject].value("counters").as[JsObject].value(
          "service.mesosphere.marathon.api.HTTPMetricsFilter.bytesRead").as[JsObject].value("count").as[Int]
      }
      val currentCounter = getCounter(result)
      val requestObj = GroupUpdate(id = Some("/empty"))
      val requestJson = Json.toJson(requestObj).toString()
      marathon.createGroup(requestObj)

      // Give some time to Kamon to take a metrics snapshot.
      Thread.sleep(3000)

      val newResult = marathon.metrics()
      val newCounter = getCounter(newResult)
      newCounter shouldBe >=(currentCounter + requestJson.length)

    }
  }

}
