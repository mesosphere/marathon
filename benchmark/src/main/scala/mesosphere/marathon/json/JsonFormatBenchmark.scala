package mesosphere.marathon
package json

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.raml.{AppInfo, EnvVarValue, MesosTaskState}
import mesosphere.marathon.state.{AbsolutePathId, Timestamp}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import play.api.libs.json.Json

/**
  * This benchmark focuses on the CPU time of AppInfoSerialization.
  *
  * GET /v2/apps?embed=apps.tasks is slow over a large number of apps (840 in our case).
  *
  * marathon-lb hammers this endpoint relentlessly. Json writing dominates CPU.
  *
  * Run with: {{{
  *   benchmark/jmh:run -t1 -f1 -wi 5 -i 10 .*JsonFormatBenchmark
  * }}}
  *
  * Or with Java Flight Recorder profiling: {{{
  *   benchmark/jmh:run -prof jmh.extras.JFR -t1 -f1 -wi 5 -i 20 .*JsonFormatBenchmark
  * }}}
  */
@State(Scope.Benchmark)
object JsonFormatBenchmark {

  /**
    * Attempts to represent a real world-ish [[AppInfo]],
    * with particular focus on accurately representing sizes of json objects,
    * which translate to expensive scala Maps.
    */
  val realisticAppInfo: AppInfo = {

    /**
      * Taken from our dev cluster with: {{{
      *   curl /v2/apps | jq '[.apps[].env | length] | add / length'
      * }}}
      */
    val numEnvVars = 70

    /**
      * Taken from our dev cluster with: {{{
      *   curl /v2/apps | jq '[.apps[].labels | length] | add / length'
      * }}}
      */
    val numLabels = 11

    val appId = AbsolutePathId("/benchmark/app/definition")

    raml.AppInfo(
      id = appId.toString,
      role = Some("someRole"),
      env = 0.to(numEnvVars).map(i => ("KEY_" * 10) + i -> EnvVarValue(("VALUE_" * 10) + i)).toMap,
      labels = 0.to(numLabels).map(i => ("KEY_" * 10) + i -> (("VALUE_" * 10) + i)).toMap,
      tasks = Some(
        Seq(
          raml.Task(
            appId.toString,
            healthCheckResults = Seq.empty,
            checkResult = None,
            host = "mesos-slave-i-039b610609702bc8a.mesos-dev.us-east-1e.example.com",
            id = "benchmark_app_definition.2e251a11-af74-11e7-8e35-12ebe3d150b4",
            ipAddresses = Seq.empty,
            ports = Seq(3000),
            servicePorts = Seq(),
            slaveId = None,
            state = MesosTaskState.TaskRunning,
            stagedAt = Some(Timestamp.now().toString),
            startedAt = Some(Timestamp.now().toString),
            version = Some(Timestamp.now().toString),
            localVolumes = Seq.empty,
            region = None,
            role = "someRole"
          )
        )
      )
    )
  }
}

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
class JsonFormatBenchmark {

  import JsonFormatBenchmark._

  @Benchmark
  def appInfo(hole: Blackhole): Unit = {
    val json = Json.toJson(realisticAppInfo)
    hole.consume(json)
  }
}
