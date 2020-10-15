package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit
import mesosphere.marathon.core.pod.{MesosContainer, BridgeNetwork}
import mesosphere.marathon.raml.{Endpoint, Image, ImageType, Resources}
import mesosphere.marathon.state.Container

import mesosphere.marathon.state._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.pod.PodDefinition
import org.openjdk.jmh.annotations.{Group => _, _}
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
object FlatDependencyBenchmark {

  val version = VersionInfo.forNewConfig(Timestamp(1))

  def makeApp(path: AbsolutePathId) =
    AppDefinition(
      id = path,
      role = "someRole",
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      networks = Seq(BridgeNetwork()),
      container = Some(Container.Docker(Nil, "alpine", List(Container.PortMapping(2015, Some(0), 10000, "tcp", Some("thing")))))
    )

  def makePod(path: AbsolutePathId) =
    PodDefinition(
      id = path,
      role = "someRole",
      networks = Seq(BridgeNetwork()),
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      containers = Seq(
        MesosContainer(
          "container-1",
          resources = Resources(1.0),
          image = Some(Image(ImageType.Docker, "alpine")),
          endpoints = List(Endpoint("service", Some(2015), Some(0), Seq("tcp")))
        )
      )
    )

  val ids = 0 to 900

  val podPaths: Vector[AbsolutePathId] = ids.iterator.map { podId =>
    AbsolutePathId(s"/pod-${podId}")
  }.toVector

  val appPaths: Vector[AbsolutePathId] = ids.iterator.map { appId =>
    AbsolutePathId(s"/app-${appId}")
  }.toVector

  val appDefs: Seq[AppDefinition] = appPaths.map { path =>
    makeApp(path)
  }

  val podDefs: Seq[PodDefinition] = podPaths.map { path =>
    makePod(path)
  }

  val rootGroup = Builders.newRootGroup(apps = appDefs, pods = podDefs)
  def upgraded = {
    val appPathId = AbsolutePathId("/app-901")
    val podPathId = AbsolutePathId("/pod-901")

    rootGroup
      .updateApp(appPathId, _ => makeApp(appPathId))
      .updatePod(podPathId, _ => makePod(podPathId))
  }
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class FlatDependencyBenchmark {
  import FlatDependencyBenchmark._

  @Benchmark
  def deploymentPlanDependencySpeed(hole: Blackhole): Unit = {
    val deployment = DeploymentPlan(rootGroup, upgraded)
    hole.consume(deployment)
  }
}
