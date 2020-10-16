package mesosphere.marathon
package state

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.pod.BridgeNetwork
import mesosphere.marathon.api.v2.{GroupNormalization, Validation}
import mesosphere.marathon.raml.{GroupConversion, Raml}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@State(Scope.Benchmark)
class GroupBenchmark {

  val version = VersionInfo.forNewConfig(Timestamp(1))
  val config: AllConf = AllConf.withTestConfig()

  def makeApp(path: AbsolutePathId) =
    AppDefinition(
      id = path,
      role = "someRole",
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      networks = Seq(BridgeNetwork()),
      container = Some(Container.Docker(Nil, "alpine", List(Container.PortMapping(2015, Some(0), 10000, "tcp", Some("thing")))))
    )

  def makeAppRaml(pathId: AbsolutePathId) = Raml.toRaml(makeApp(pathId))

  //@Param(value = Array("2", "10", "100", "1000"))
  @Param(value = Array("10"))
  var appsPerGroup: Int = _
  lazy val appIds = 0 until appsPerGroup

  //@Param(value = Array("5", "10", "20"))
  @Param(value = Array("5"))
  var groupDepth: Int = _
  lazy val groupIds = 0 until groupDepth

  @Param(value = Array("5"))
  var groupsPerLevel: Int = _

  lazy val groupPaths: Vector[AbsolutePathId] = groupIds.foldLeft(Vector[AbsolutePathId]()) { (allPaths, nextChild) =>
    val nextChildPath = allPaths.lastOption.getOrElse(PathId.root) / s"group-$nextChild"
    allPaths :+ nextChildPath
  }

  lazy val rootGroup: RootGroup = fillRootGroup()

  lazy val groupRaml: raml.GroupUpdate = {
    raml.GroupUpdate(id = Some("/"), groups = Some(buildChildGroups(0, PathId.root)))
  }

  def buildChildGroups(level: Int, parent: AbsolutePathId): Set[raml.GroupUpdate] = {
    if (level == groupDepth) return Set.empty

    (0 to groupsPerLevel).map { gid =>
      val groupPath = parent / s"group-$gid"
      raml.GroupUpdate(
        id = Some(groupPath.toString),
        apps = Some((0 to appsPerGroup).map { aid => makeAppRaml(groupPath / s"app-$aid") }.toSet),
        groups = Some(buildChildGroups(level + 1, groupPath))
      )
    }.toSet
  }

  // Create apps and add them to each group on each level
  def fillRootGroup(): RootGroup = {
    var tmpGroup = RootGroup.empty()
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

  @Benchmark
  def validateRootGroup(hole: Blackhole): Unit = {
    Validation.validateOrThrow(rootGroup)(RootGroup.validRootGroup(AllConf.withTestConfig()))
  }

  @Benchmark
  def serializationRoundtrip(hole: Blackhole): Unit = {
    val normalizedGroup = GroupNormalization(config, rootGroup).updateNormalization(PathId.root).normalized(groupRaml)
    val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]
    val converted = GroupConversion(normalizedGroup, rootGroup, version.version).apply(appConversionFunc)
    hole.consume(converted)
  }
}
