package mesosphere.marathon
package state

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.pod.BridgeNetwork
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@State(Scope.Benchmark)
class GroupBenchmark {

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

  @Param(value = Array("2", "10", "100", "1000"))
  var appsPerGroup: Int = _
  lazy val appIds = 0 until appsPerGroup

  @Param(value = Array("5", "10", "20"))
  var groupDepth: Int = _
  lazy val groupIds = 0 until groupDepth

  lazy val groupPaths: Vector[PathId] = groupIds.foldLeft(Vector[PathId]()) { (allPaths, nextChild) =>
    val nextChildPath = allPaths.lastOption.getOrElse(PathId.empty) / s"group-$nextChild"
    allPaths :+ nextChildPath
  }

  lazy val rootGroup: RootGroup = fillRootGroup()

  // Create apps and add them to each group on each level
  def fillRootGroup(): RootGroup = {
    var tmpGroup = RootGroup()
    groupPaths.foreach { groupPath =>
      appIds.foreach { appId =>
        val path = groupPath / s"app-${appId}"
        val app = makeApp(path)
        // Groups will be created
        tmpGroup = tmpGroup.updateApp(path, (maybeApp) => app) // because we create an app, you know.
      }
    }
    tmpGroup
  }

  val random = new Random(seed = 1337)
  def randomGroupId() = groupPaths(random.nextInt(groupDepth))
  def randomApp() = {
    val randomApp = random.nextInt(appsPerGroup)
    randomGroupId() / s"app-$randomApp"
  }
}

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
class RootGroupBenchmark extends GroupBenchmark {

  @Benchmark
  def accessRandomGroupAndApp(): Unit = {
    rootGroup.group(randomGroupId())
    rootGroup.app(randomApp())
  }

  @Benchmark
  def buildRootGroup(hole: Blackhole): Unit = {
    val root = fillRootGroup()
    hole.consume(root)
  }
}
