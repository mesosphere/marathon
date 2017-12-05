package mesosphere.marathon
package json

import java.util.concurrent.TimeUnit

import mesosphere.marathon.api.v2.json.AppAndGroupFormats
import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask }
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ LaunchedOnReservation, Reservation, Status }
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ AppDefinition, EnvVarString, PathId, Timestamp }
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import play.api.libs.json.Json

/**
  * This benchmark focuses on the CPU time of [[AppAndGroupFormats.ExtendedAppInfoWrites]].
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
object JsonFormatBenchmark extends AppAndGroupFormats {

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

    val appId = PathId("benchmark/app/definition")

    AppInfo(
      app = AppDefinition(
        id = appId,
        env = 0.to(numEnvVars).map(i => ("KEY_" * 10) + i -> EnvVarString(("VALUE_" * 10) + i)).toMap,
        labels = 0.to(numLabels).map(i => ("KEY_" * 10) + i -> (("VALUE_" * 10) + i)).toMap
      ),
      maybeTasks = Some(
        Seq(
          EnrichedTask(
            appId,
            task = LaunchedOnReservation(
              taskId = Task.Id("benchmark_app_definition.2e251a11-af74-11e7-8e35-12ebe3d150b4"),
              runSpecVersion = Timestamp.now(),
              status = Status(
                stagedAt = Timestamp.now(),
                startedAt = Some(Timestamp.now()),
                mesosStatus = None,
                condition = Condition.Running,
                networkInfo = NetworkInfo(
                  hostName = "mesos-slave-i-039b610609702bc8a.mesos-dev.us-east-1e.example.com",
                  hostPorts = Seq(30000),
                  ipAddresses = Seq.empty
                )
              ),
              reservation = Reservation(
                volumeIds = Seq.empty,
                state = Reservation.State.Launched
              )
            ),
            agentInfo = AgentInfo(
              host = "mesos-slave-i-039b610609702bc8a.mesos-dev.us-east-1e.example.com",
              agentId = None,
              region = None,
              zone = None,
              attributes = Seq.empty
            ),
            healthCheckResults = Seq.empty
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

