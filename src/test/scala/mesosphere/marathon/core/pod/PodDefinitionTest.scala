package mesosphere.marathon
package core.pod

import mesosphere.UnitTest
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.{AbsolutePathId, ResourceLimits}

class PodDefinitionTest extends UnitTest {
  "PodDefinition" should {
    "compute resources with a decimal point" in {
      val pod = PodDefinition(
        AbsolutePathId("/test"),
        role = "*",
        executorResources = Resources(cpus = 0.1),
        containers = (1 to 7).map(n => MesosContainer(n.toString, resources = Resources(cpus = 0.1)))
      )
      pod.resources.cpus should be(0.8)
    }

    "compute GPU resources with both executor and container resources requested" in {
      val pod = PodDefinition(
        AbsolutePathId("/test"),
        role = "*",
        executorResources = Resources(gpus = 1),
        containers = (1 to 2).map(n => MesosContainer(n.toString, resources = Resources(gpus = 1)))
      )
      pod.resources.cpus should be(3)
    }

    "resourceLimits aggregation" in {
      "returns none when no resource limits specified" in {
        PodDefinition.aggregateResourceLimits(Nil) shouldBe None
      }

      "returns cpu only when no mem limits specified" in {
        PodDefinition.aggregateResourceLimits(Seq(ResourceLimits(cpus = Some(Double.PositiveInfinity), mem = None))) shouldBe Some(ResourceLimits(cpus = Some(Double.PositiveInfinity), mem = None))
      }

      "aggregates multiple unlimited values as unlimited" in {
        val unlimitedCpu = ResourceLimits(cpus = Some(Double.PositiveInfinity), mem = None)
        PodDefinition.aggregateResourceLimits(Seq(unlimitedCpu, unlimitedCpu)) shouldBe Some(unlimitedCpu)
      }
    }
  }
}
