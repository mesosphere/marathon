package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state.AppDefinition.AppKey
import mesosphere.marathon.state.Group.GroupKey
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.openjdk.jmh.annotations.{ Group => _, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.collection.breakOut
import scala.util.Random

@State(Scope.Benchmark)
object DependencyGraphBenchmark {
  val r = new Random(1000)

  val superGroupIds = 0 to 4 // no interdependencies here
  val groupIds = 0 to 5
  val appIds = 0 to 10
  val version1 = VersionInfo.forNewConfig(Timestamp(1))
  val version2 = VersionInfo.forNewConfig(Timestamp(2))

  val superGroups: Map[GroupKey, Group] = superGroupIds.map { superGroupId =>

    val paths: Vector[Vector[PathId]] =
      groupIds.map { groupId =>
        appIds.map { appId =>
          s"/supergroup-${superGroupId}/group-${groupId}/app-${appId}".toPath
        }.toVector
      }(breakOut)

    val appDefs: Map[AppKey, AppDefinition] =
      groupIds.flatMap { groupId =>
        appIds.map { appId =>
          val dependencies = for {
            depGroupId <- groupIds if depGroupId < groupId
            depAppId <- appIds
            if r.nextBoolean
          } yield paths(depGroupId)(depAppId)

          val path = paths(groupId)(appId)
          path -> AppDefinition(
            id = path,
            dependencies = dependencies.toSet,
            labels = Map("ID" -> appId.toString),
            versionInfo = version1
          )
        }(breakOut)
      }(breakOut)

    val subGroups: Map[GroupKey, Group] = groupIds.map { groupId =>
      val id = s"/supergroup-${superGroupId}/group-${groupId}".toPath
      id -> Group(
        id = id,
        transitiveAppsById = appDefs,
        transitivePodsById = Map.empty)
    }(breakOut)

    val id = s"/supergroup-${superGroupId}".toPath
    id -> Group(
      id = id,
      groupsById = subGroups,
      transitiveAppsById = subGroups.flatMap(_._2.transitiveAppsById)(breakOut),
      transitivePodsById = Map.empty)
  }(breakOut)

  val rootGroup = RootGroup(
    groupsById = superGroups)

  val upgraded = RootGroup(
    groupsById = superGroups.map {
      case (superGroupId, superGroup) =>
        if (superGroupId == "/supergroup-0".toPath) {
          superGroupId -> Group(
            id = superGroupId,
            groupsById = superGroup.groupsById.map {
              case (id, subGroup) =>
                id -> Group(
                  id = id,
                  transitiveAppsById = subGroup.transitiveAppsById.mapValues(_.copy(versionInfo = version2)),
                  transitivePodsById = Map.empty)
            },
            transitiveAppsById = superGroup.groupsById.flatMap { case (_, group) => group.transitiveAppsById.mapValues(_.copy(versionInfo = version2)) },
            transitivePodsById = Map.empty
          )
        } else {
          superGroupId -> superGroup
        }
    }(breakOut)
  )
}

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@Fork(1)
class DependencyGraphBenchmark {
  import DependencyGraphBenchmark._

  @Benchmark
  def deploymentPlanDependencySpeed(hole: Blackhole): Unit = {
    val deployment = DeploymentPlan(rootGroup, upgraded)
    hole.consume(deployment)
  }
}
