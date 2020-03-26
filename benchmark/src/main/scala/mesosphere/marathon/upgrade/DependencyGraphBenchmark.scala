package mesosphere.marathon
package upgrade

import java.util.concurrent.TimeUnit

import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state._
import org.openjdk.jmh.annotations.{Group => _, _}
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@State(Scope.Benchmark)
object DependencyGraphBenchmark {
  val r = new Random(1000)

  val superGroupIds = 0 to 4 // no interdependencies here
  val groupIds = 0 to 5
  val appIds = 0 to 10
  val version1 = VersionInfo.forNewConfig(Timestamp(1))
  val version2 = VersionInfo.forNewConfig(Timestamp(2))

  val superGroups: Map[AbsolutePathId, Group] = superGroupIds.iterator.map { superGroupId =>

    val paths: Vector[Vector[AbsolutePathId]] =
      groupIds.iterator.map { groupId =>
        appIds.iterator.map { appId =>
          AbsolutePathId(s"/supergroup-${superGroupId}/group-${groupId}/app-${appId}")
        }.toVector
      }.toVector

    val subGroups: Map[AbsolutePathId, Group] = groupIds.iterator.map { groupId =>
      val id = AbsolutePathId(s"/supergroup-${superGroupId}/group-${groupId}")
      id -> Group(id = id)
    }.toMap

    val id = AbsolutePathId(s"/supergroup-${superGroupId}")
    id -> Group(
      id = id,
      groupsById = subGroups
    )
  }.toMap

  val rootGroup = RootGroup(
    groupsById = superGroups)

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
    }
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
