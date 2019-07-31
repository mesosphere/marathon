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

  def makeApp(path: PathId) =
    AppDefinition(
      id = path,
      role = "someRole",
      labels = Map("ID" -> path.toString),
      versionInfo = version,
      networks = Seq(BridgeNetwork()),
      container = Some(
        Container.Docker(Nil, "alpine", List(Container.PortMapping(2015, Some(0), 10000, "tcp", Some("thing")))))
    )

  def makeAppRaml(pathId: PathId) = Raml.toRaml(makeApp(pathId))

  //@Param(value = Array("2", "10", "100", "1000"))
  @Param(value = Array("2"))
  var appsPerGroup: Int = _
  lazy val appIds = 0 until appsPerGroup

  //@Param(value = Array("5", "10", "20"))
  @Param(value = Array("5"))
  var groupDepth: Int = _
  lazy val groupIds = 0 until groupDepth

  @Param(value = Array("5"))
  var groupsPerLevel: Int = _

  lazy val groupPaths: Vector[PathId] = groupIds.foldLeft(Vector[PathId]()) { (allPaths, nextChild) =>
    val nextChildPath = allPaths.lastOption.getOrElse(PathId.root) / s"group-$nextChild"
    allPaths :+ nextChildPath
  }

  lazy val rootGroup: RootGroup = fillRootGroup()

  lazy val groupRaml: raml.GroupUpdate = groupIds.foldLeft(raml.GroupUpdate(id = Some("/first"))) { (acc, nextChildId) =>
    val groupPath = PathId(acc.id.get) / s"group-$nextChildId"
    val apps = appIds.map { appId =>
      val path = groupPath / s"app-${appId}"
      makeAppRaml(path)
    }.toSet
    val nextChild = raml.GroupUpdate(id = Some(groupPath.toString), apps = Some(apps))
    acc.copy(groups = acc.groups.map(_ + nextChild))
  }

  def buildGroupRaml(level: Int, parent: AbsolutePathId): raml.GroupUpdate = {
    0 to groupsPerLevel map { gid =>
      val groupPath = parent /  
    }
  }

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

  @Benchmark
  def validateRootGroup(hole: Blackhole): Unit = {
    Validation.validateOrThrow(rootGroup)(RootGroup.validRootGroup(AllConf.withTestConfig()))
  }

  @Benchmark
  def serializationRoundtrip(hole: Blackhole): Unit = {
    val normalized = GroupNormalization.updateNormalization(config, PathId.root).normalized(groupRaml)
    val groupValidator = Group.validNestedGroupUpdateWithBase(PathId.root, rootGroup)
    groupValidator(normalized)
    val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]
    val converted = Raml.fromRaml(
      GroupConversion(normalized, rootGroup, version.version) -> appConversionFunc)
    println(s"Group tree:\n ${converted.prettyTree()}")
    hole.consume(converted)
  }
}
