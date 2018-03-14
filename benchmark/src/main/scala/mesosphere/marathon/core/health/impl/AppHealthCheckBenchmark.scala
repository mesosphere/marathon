package mesosphere.marathon
package core.health.impl

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.health.{Health, MarathonHttpHealthCheck, PortReference, impl}
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.{ApplicationKey, InstanceKey}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.PathId
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import mesosphere.marathon.state.Timestamp

object AppHealthCheckBenchmark {
  private final val NB_APPLICATIONS = 1000
  private final val NB_VERSIONS_PER_APPLICATION = 3

  // this number is used for head revision of the app only
  private final val NB_INSTANCES_PER_APPLICATION = 10
  private final val NB_INSTANCES = NB_APPLICATIONS * NB_INSTANCES_PER_APPLICATION

  private final val NB_STATUS_UPDATES = 10000

  val randomGenerator = scala.util.Random

  val healthChecks = Seq(
    MarathonHttpHealthCheck(portIndex = Some(PortReference(80))),
    MarathonHttpHealthCheck(portIndex = Some(PortReference(443))),
    MarathonHttpHealthCheck(portIndex = Some(PortReference(8080)))
  )
  val applicationKeys = 0.to(NB_APPLICATIONS).map(appId => {
    0.to(NB_VERSIONS_PER_APPLICATION).map(version => Timestamp(version.toLong))
      .map(version => ApplicationKey(PathId(s"$appId"), version))
  }).flatten

  val shuffledApplicationKeys = scala.util.Random.shuffle(applicationKeys)

  val instanceKeys = applicationKeys.map(appKey => {
    0.to(NB_INSTANCES_PER_APPLICATION).map(instanceId => InstanceKey(appKey, Instance.Id(s"$instanceId")))
  }).flatten

  val randomlySelectedInstanceKeysWithStatus = 0.to(NB_STATUS_UPDATES).map(idx => {
    val instanceIdx = randomGenerator.nextInt(NB_INSTANCES)
    val hcIdx = instanceIdx % 3
    val healthSelector = instanceIdx % 2
    val instanceKey = instanceKeys(instanceIdx)
    val failingHealth = Health(instanceKey.instanceId, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(10)))
    val successHealth = Health(instanceKey.instanceId, lastSuccess = Some(Timestamp(5)), lastFailure = Some(Timestamp(0)))
    val health = healthSelector match {
      case 0 => successHealth
      case _ => failingHealth
    }
    (instanceKey.applicationKey, healthChecks(hcIdx), health)
  })
  val doNothingNotifier = (h: Option[Boolean]) => {}

  val appHealthCheckProxy = new impl.AppHealthCheckActor.AppHealthCheckProxy
  val appHealthCheckProxyWithAlreadyRegisteredHealthChecks = {
    val proxy = new impl.AppHealthCheckActor.AppHealthCheckProxy
    for(
      applicationKey <- applicationKeys;
      healthCheck <- healthChecks
    ) {
      proxy.addHealthCheck(applicationKey, healthCheck)
    }
    proxy
  }
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class AppHealthCheckBenchmark {
  import AppHealthCheckBenchmark._

  /**
    * Simulate Marathon starting and registering all health checks
    * @param hole
    */
  @Benchmark
  def addHealthChecks(hole: Blackhole): Unit = {
    for(
      applicationKey <- shuffledApplicationKeys;
      healthCheck <- healthChecks
    ) {
      appHealthCheckProxy.addHealthCheck(applicationKey, healthCheck)
    }
  }

  /**
    * Simulate all health checks reporting at the same time, which is unlickely
    * to happen but give an idea on the performance of this method.
    * @param hole
    */
  @Benchmark
  def updateHealthCheckStatuses(hole: Blackhole): Unit = {
    for(
      update <- randomlySelectedInstanceKeysWithStatus
    ) {
      appHealthCheckProxyWithAlreadyRegisteredHealthChecks
        .updateHealthCheckStatus(update._1, update._2, update._3, doNothingNotifier)
    }
  }
}
