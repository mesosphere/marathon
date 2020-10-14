package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state._
import org.openjdk.jmh.annotations.{Group => _, _}
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

  val superGroups: Map[AbsolutePathId, Group] = superGroupIds.map { superGroupId =>
    val paths: Vector[Vector[AbsolutePathId]] =
      groupIds.map { groupId =>
        appIds.map { appId =>
          AbsolutePathId(s"/supergroup-${superGroupId}/group-${groupId}/app-${appId}")
        }.toVector
      }(breakOut)

    val appDefs: Map[AbsolutePathId, AppDefinition] =
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
            role = "someRole",
            dependencies = dependencies.toSet,
            labels = Map("ID" -> appId.toString),
            versionInfo = version1
          )
        }(breakOut)
      }(breakOut)

    val subGroups: Map[AbsolutePathId, Group] = groupIds.map { groupId =>
      val id = AbsolutePathId(s"/supergroup-${superGroupId}/group-${groupId}")
      id -> Group(id = id)
    }(breakOut)

    val id = AbsolutePathId(s"/supergroup-${superGroupId}")
    id -> Group(
      id = id,
      groupsById = subGroups
    )
  }(breakOut)

  val rootGroup = RootGroup(groupsById = superGroups)

  val upgraded = RootGroup(
    groupsById = superGroups.map {
      case (superGroupId, superGroup) =>
        if (superGroupId == AbsolutePathId("/supergroup-0")) {
          superGroupId -> Group(
            id = superGroupId,
            groupsById = superGroup.groupsById.map {
              case (id, subGroup) =>
                id -> Group(id = id)
            }
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
