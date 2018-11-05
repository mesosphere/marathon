package mesosphere.marathon
package core.pod

import mesosphere.UnitTest
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId

class PodDefinitionTest extends UnitTest {
  "PodDefinition" should {
    "compute resources with a decimal point" in {
      val pod = PodDefinition(PathId("/test"), executorResources = Resources(cpus = 0.1), containers = (1 to 7).map(n => MesosContainer(n.toString, resources = Resources(cpus = 0.1))))
      pod.resources.cpus should be (0.8)
    }

    "compute GPU resources with both executor and container resources requested" in {
      val pod = PodDefinition(PathId("/test"), executorResources = Resources(gpus = 1), containers = (1 to 2).map(n => MesosContainer(n.toString, resources = Resources(gpus = 1))))
      pod.resources.cpus should be (3)
    }
  }
}
