package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit
import mesosphere.marathon.core.pod.{ MesosContainer, BridgeNetwork }
import mesosphere.marathon.raml.{ Endpoint, Image, ImageType, Resources }
import mesosphere.marathon.state.Container

import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.core.pod.PodDefinition
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.collection.breakOut

@State(Scope.Benchmark)
class GroupVersioningUtilBenchmark {

  val version = VersionInfo.forNewConfig(Timestamp(1))

  def makeApp(path: PathId) =
    AppDefinition(
      id = path,
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      networks = Seq(BridgeNetwork()),
      container = Some(
        Container.Docker(Nil, "alpine", List(Container.PortMapping(2015, Some(0), 10000, "tcp", Some("thing")))))
    )

  def makePod(path: PathId) =
    PodDefinition(
      id = path,
      networks = Seq(BridgeNetwork()),
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

  @Param(value = Array("100", "5000"))
  var numberOfSavedApps: Int = _
  lazy val ids = 0 until numberOfSavedApps

  @Param(value = Array("5", "15"))
  var numberOfGroups: Int = _
  lazy val groupIds = 0 until numberOfGroups

  lazy val childGroupPaths: Vector[PathId] = groupIds.map { groupId =>
    s"group-$groupId".toRootPath
  }(breakOut)

  lazy val rootGroup: RootGroup = fillRootGroup()

  // Create apps and add them to their groups
  def fillRootGroup(): RootGroup = {
    var tmpGroup = RootGroup()
    ids.foreach { appId =>
      val groupPath = childGroupPaths(appId % numberOfGroups)
      val path = groupPath / s"app-${appId}"
      val app = makeApp(path)
      tmpGroup = tmpGroup.updateApp(path, (maybeApp) => app) // because we create an app, you know.
    }
    tmpGroup
  }

  //  lazy val podPaths: Vector[PathId] = ids.map { podId =>
  //    val groupPath = childGroupPaths(podId % numberOfGroups)
  //    groupPath / s"pod-${podId}"
  //  }(breakOut)

  //  lazy val podDefs: Map[PathId, PodDefinition] = podPaths.map { path =>
  //    path -> makePod(path)
  //  }(breakOut)

  def upgraded = {
    val appId = childGroupPaths(0) / s"app-$numberOfSavedApps"
    rootGroup.updateApp(appId, (maybeApp) => makeApp(appId))
  }
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class GroupVersioningUtilBenchmark1 extends GroupVersioningUtilBenchmark {

  @Benchmark
  def updateVersionInfoForChangedApps(hole: Blackhole): Unit = {
    println(rootGroup.groupsById.keys)
    val newRootGroup = GroupVersioningUtil.updateVersionInfoForChangedApps(
      Timestamp (2),
      rootGroup, upgraded)
    hole.consume(newRootGroup)
  }
}
