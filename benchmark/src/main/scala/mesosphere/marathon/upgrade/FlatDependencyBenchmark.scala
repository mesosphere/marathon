package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit
import scala.collection.breakOut

import mesosphere.marathon.core.pod.{ ContainerNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.raml.{ Endpoint, Image, ImageType, Resources }
import mesosphere.marathon.state._
import mesosphere.marathon.state.PathId._
import org.apache.mesos.Protos.ContainerInfo
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
object FlatDependencyBenchmark {

  val version = VersionInfo.forNewConfig(Timestamp(1))

  def makeApp(path: PathId) =
    AppDefinition(
      id = path,
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      container = Some(
        Container.Docker(Nil, "alpine",
          network = Some(ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = List(Container.PortMapping(2015, Some(0), 10000, "tcp", Some("thing")))))
    )

  def makePod(path: PathId) =
    PodDefinition(
      id = path,
      networks = Seq(ContainerNetwork("bridge")),
      labels = Map("ID" -> path.toString),
      version = version.lastConfigChangeAt,

      containers = Seq(
        MesosContainer(
          "container-1",
          resources = Resources(1.0),
          image = Some(Image(ImageType.Docker, "alpine")),
          endpoints = List(
            Endpoint(
              "service",
              Some(2015),
              Some(0),
              Seq("tcp"))))))

  val ids = 0 to 900

  val podPaths: Vector[PathId] = ids.map { podId =>
    s"/pod-${podId}".toPath
  }(breakOut)

  val appPaths: Vector[PathId] = ids.map { appId =>
    s"/app-${appId}".toPath
  }(breakOut)

  val appDefs: Map[PathId, AppDefinition] = appPaths.map { path =>
    path -> makeApp(path)
  }(breakOut)

  val podDefs: Map[PathId, PodDefinition] = podPaths.map { path =>
    path -> makePod(path)
  }(breakOut)

  val rootGroup = RootGroup(apps = appDefs, pods = podDefs)
  def upgraded = {
    val pathId = "/app-901".toPath
    RootGroup(
      apps = rootGroup.apps + (pathId -> makeApp(pathId)),
      pods = rootGroup.pods + (pathId -> makePod(pathId))
    )
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
