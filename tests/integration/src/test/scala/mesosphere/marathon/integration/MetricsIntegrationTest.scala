package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.GroupUpdate
import play.api.libs.json.{JsObject, JsString, Json}

class MetricsIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  "Marathon metrics" should {
    "have metrics" in {
      When("the metrics endpoint is queried")
      val result = marathon.metrics()

      Then("Marathon responds as expected")
      result should be(OK)
      val mediaType = result.originalResponse.entity.getContentType().mediaType
      mediaType.mainType shouldBe "application"
      mediaType.subType shouldBe "json"

      val json = result.entityJson.as[JsObject]
      val version = (json \ "version").as[JsString].value
      version shouldBe "4.0.0"

      val counters = (json \ "counters").as[JsObject].keys
      counters should contain("marathon.deployments.counter")
      counters should contain("marathon.deployments.dismissed.counter")
      counters should contain("marathon.persistence.gc.runs.counter")
      counters should contain("marathon.tasks.launched.counter")
      counters should contain("marathon.mesos.calls.revive.counter")
      counters should contain("marathon.mesos.calls.suppress.counter")
      counters should contain("marathon.mesos.offer-operations.launch-group.counter")
      counters should contain("marathon.mesos.offer-operations.launch.counter")
      counters should contain("marathon.mesos.offer-operations.reserve.counter")
      counters should contain("marathon.mesos.offers.declined.counter")
      counters should contain("marathon.mesos.offers.incoming.counter")
      counters should contain("marathon.mesos.offers.used.counter")
      counters should contain("marathon.http.requests.size.counter.bytes")
      counters should contain("marathon.http.responses.size.counter.bytes")
      counters should contain("marathon.http.responses.size.gzipped.counter.bytes")

      val gauges = (json \ "gauges").as[JsObject].keys
      gauges should contain("marathon.apps.active.gauge")
      gauges should contain("marathon.deployments.active.gauge")
      gauges should contain("marathon.groups.active.gauge")
      gauges should contain("marathon.leadership.duration.gauge.seconds")
      gauges should contain("marathon.instances.running.gauge")
      gauges should contain("marathon.instances.staged.gauge")
      gauges should contain("marathon.instances.launch-overdue.gauge")
      gauges should contain("marathon.instances.inflight-kills.gauge")
      gauges should contain("marathon.instances.inflight-kill-attempts.gauge")
      gauges should contain("marathon.uptime.gauge.seconds")
      gauges should contain("marathon.http.requests.active.gauge")
      gauges should contain("marathon.jvm.buffers.mapped.gauge")
      gauges should contain("marathon.jvm.buffers.mapped.capacity.gauge.bytes")
      gauges should contain("marathon.jvm.buffers.mapped.memory.used.gauge.bytes")
      gauges should contain("marathon.jvm.buffers.direct.gauge")
      gauges should contain("marathon.jvm.buffers.direct.capacity.gauge.bytes")
      gauges should contain("marathon.jvm.buffers.direct.memory.used.gauge.bytes")
      gauges should contain("marathon.jvm.memory.total.init.gauge.bytes")
      gauges should contain("marathon.jvm.memory.total.used.gauge.bytes")
      gauges should contain("marathon.jvm.memory.total.max.gauge.bytes")
      gauges should contain("marathon.jvm.memory.total.committed.gauge.bytes")
      gauges should contain("marathon.jvm.memory.heap.init.gauge.bytes")
      gauges should contain("marathon.jvm.memory.heap.used.gauge.bytes")
      gauges should contain("marathon.jvm.memory.heap.max.gauge.bytes")
      gauges should contain("marathon.jvm.memory.heap.committed.gauge.bytes")
      gauges should contain("marathon.jvm.memory.heap.usage.gauge")
      gauges should contain("marathon.jvm.memory.non-heap.init.gauge.bytes")
      gauges should contain("marathon.jvm.memory.non-heap.used.gauge.bytes")
      gauges should contain("marathon.jvm.memory.non-heap.max.gauge.bytes")
      gauges should contain("marathon.jvm.memory.non-heap.committed.gauge.bytes")
      gauges should contain("marathon.jvm.memory.non-heap.usage.gauge")
      gauges should contain("marathon.jvm.threads.active.gauge")
      gauges should contain("marathon.jvm.threads.daemon.gauge")
      gauges should contain("marathon.jvm.threads.deadlocked.gauge")
      gauges should contain("marathon.jvm.threads.new.gauge")
      gauges should contain("marathon.jvm.threads.runnable.gauge")
      gauges should contain("marathon.jvm.threads.blocked.gauge")
      gauges should contain("marathon.jvm.threads.timed-waiting.gauge")
      gauges should contain("marathon.jvm.threads.waiting.gauge")
      gauges should contain("marathon.jvm.threads.terminated.gauge")

      val meters = (json \ "meters").as[JsObject].keys
      meters should contain("marathon.http.responses.1xx.rate.meter")
      meters should contain("marathon.http.responses.2xx.rate.meter")
      meters should contain("marathon.http.responses.3xx.rate.meter")
      meters should contain("marathon.http.responses.4xx.rate.meter")
      meters should contain("marathon.http.responses.5xx.rate.meter")

      val timers = (json \ "timers").as[JsObject].keys
      timers should contain("marathon.persistence.gc.compaction.duration.timer.seconds")
      timers should contain("marathon.persistence.gc.scan.duration.timer.seconds")
      timers should contain("marathon.http.requests.duration.timer.seconds")
      timers should contain("marathon.http.requests.get.duration.timer.seconds")
      timers should contain("marathon.http.requests.post.duration.timer.seconds")
      timers should contain("marathon.http.requests.put.duration.timer.seconds")
      timers should contain("marathon.http.requests.delete.duration.timer.seconds")
    }

    "have metrics in the Prometheus format" in {
      When("the Prometheus metrics endpoint is queried")
      val result = marathon.prometheusMetrics()

      Then("Marathon responds as expected")
      result should be(OK)
      val mediaType = result.originalResponse.entity.getContentType().mediaType
      mediaType.mainType shouldBe "text"
      mediaType.subType shouldBe "plain"

      val lines = result.entityString.split('\n').filter(_.nonEmpty).toSet

      lines should contain("# TYPE marathon_deployments_counter counter")
      lines should contain("marathon_deployments_counter 0")

      lines should contain("# TYPE marathon_apps_active_gauge gauge")
      lines should contain("marathon_apps_active_gauge 0")

      lines should contain("# TYPE marathon_http_responses_1xx_rate_meter_count gauge")
      lines should contain("marathon_http_responses_1xx_rate_meter_count 0")
      lines should contain("# TYPE marathon_http_responses_1xx_rate_meter_mean_rate gauge")
      lines should contain("marathon_http_responses_1xx_rate_meter_mean_rate 0.0")
      lines should contain("# TYPE marathon_http_responses_1xx_rate_meter_m1_rate gauge")
      lines should contain("marathon_http_responses_1xx_rate_meter_m1_rate 0.0")
      lines should contain("# TYPE marathon_http_responses_1xx_rate_meter_m5_rate gauge")
      lines should contain("marathon_http_responses_1xx_rate_meter_m5_rate 0.0")
      lines should contain("# TYPE marathon_http_responses_1xx_rate_meter_m15_rate gauge")
      lines should contain("marathon_http_responses_1xx_rate_meter_m15_rate 0.0")

      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds summary")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.5\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.75\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.95\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.98\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.99\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds{quantile=\"0.999\"} 0.000000")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_count 0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_min gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_min 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_mean gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_mean 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_max gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_max 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_stddev gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_stddev 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_mean_rate gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_mean_rate 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_m1_rate gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_m1_rate 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_m5_rate gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_m5_rate 0.0")
      lines should contain("# TYPE marathon_debug_persistence_operations_delete_duration_timer_seconds_m15_rate gauge")
      lines should contain("marathon_debug_persistence_operations_delete_duration_timer_seconds_m15_rate 0.0")
    }

    "correctly count outgoing HTTP bytes" in {

      When("the metrics endpoint is queried")
      val result = marathon.metrics()

      Then("the system responds as expected")
      result should be(OK)
      result.entityJson.as[JsObject].keys should contain("counters")
      result.entityJson("counters").as[JsObject].keys should contain("marathon.http.responses.size.counter.bytes")

      And("the `outputBytes` is increased as expected")
      val currentCounter = result.entityJson("counters")("marathon.http.responses.size.counter.bytes")("count").as[Int]

      // Give some time to the metric to get updated.
      Thread.sleep(3000)

      val newResult = marathon.metrics()
      val newCounter = newResult.entityJson("counters")("marathon.http.responses.size.counter.bytes")("count").as[Int]
      newCounter shouldBe >=(currentCounter + result.entityString.length)

    }

    "correctly count incoming HTTP bytes" in {

      When("the metrics endpoint is queried")
      val result = marathon.metrics()

      Then("the system responds as expected")
      result should be(OK)
      result.entityJson.as[JsObject].keys should contain("counters")
      result.entityJson("counters").as[JsObject].keys should contain("marathon.http.requests.size.counter.bytes")

      And("the `inputBytes` is increased as expected")
      val currentCounter = result.entityJson("counters")("marathon.http.requests.size.counter.bytes")("count").as[Int]
      val requestObj = GroupUpdate(id = Some("/empty"))
      val requestJson = Json.toJson(requestObj).toString()
      marathon.createGroup(requestObj)

      // Give some time to the metric to get updated.
      Thread.sleep(3000)

      val newResult = marathon.metrics()
      val newCounter = newResult.entityJson("counters")("marathon.http.requests.size.counter.bytes")("count").as[Int]
      newCounter shouldBe >=(currentCounter + requestJson.length)

    }
  }

}
