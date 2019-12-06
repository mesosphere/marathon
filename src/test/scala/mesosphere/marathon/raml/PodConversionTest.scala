package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod.{ContainerNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.raml.PodStatusConversionTest.containerResources
import mesosphere.marathon.state.AbsolutePathId

class PodConversionTest extends UnitTest {

  import PodConversionTest._

  "PodConversion" should {
    val pod = basicOneContainerPod.copy(linuxInfo = Some(state.LinuxInfo(seccomp = None, ipcInfo = Some(state.IPCInfo(ipcMode = state.IpcMode.Private, shmSize = Some(32))))))

    "converting raml to internal model" should {
      "keep linux info on executor" in {
        val ramlPod = pod.toRaml
        val ramlLinuxInfo = Some(LinuxInfo(seccomp = None, ipcInfo = Some(IPCInfo(mode = IPCMode.Private, shmSize = Some(32)))))
        ramlPod.linuxInfo should be(ramlLinuxInfo)
      }

      "keeps legacySharedResources when it is set to true, but drops it when set to false" in {
        val ramlPod = pod.toRaml
        ramlPod.copy(legacySharedResources = Some(false)).fromRaml.legacySharedResources shouldBe None
        ramlPod.copy(legacySharedResources = Some(true)).fromRaml.legacySharedResources shouldBe Some(true)
      }
    }

    "converting internal model to raml" should {
      "keeps legacySharedResources when it is set to true, but drops it when set to false" in {
        pod.copy(legacySharedResources = Some(false)).toRaml.legacySharedResources shouldBe None
        pod.copy(legacySharedResources = Some(true)).toRaml.legacySharedResources shouldBe Some(true)
      }
    }
    behave like convertToRamlAndBack(pod)
  }

  def convertToRamlAndBack(pod: PodDefinition): Unit = {
    s"pod ${pod.id.toString} is written to json and can be read again via formats" in {
      Given("An pod")
      val ramlPod = pod.toRaml[Pod]

      When("The pod is translated to json and read back from formats")
      val readPod: PodDefinition = withValidationClue {
        Raml.fromRaml(ramlPod)
      }
      Then("The pod is identical")
      readPod should be(pod)
    }
  }

  def withValidationClue[T](f: => T): T = scala.util.Try { f }.recover {
    // handle RAML validation errors
    case vfe: ValidationFailedException => fail(vfe.failure.violations.toString())
    case th => throw th
  }.get
}

object PodConversionTest {
  val basicOneContainerPod = PodDefinition(
    id = AbsolutePathId("/foo"),
    role = "*",
    containers = Seq(
      MesosContainer(
        name = "ct1",
        resources = containerResources,
        image = Some(Image(kind = ImageType.Docker, id = "busybox")),
        endpoints = Seq(
          Endpoint(name = "web", containerPort = Some(80)),
          Endpoint(name = "admin", containerPort = Some(90), hostPort = Some(0))
        ),
        healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("web")), path = Some("/ping")))
      )
    ),
    networks = Seq(ContainerNetwork(name = "dcos"), ContainerNetwork("bigdog"))
  )
}
