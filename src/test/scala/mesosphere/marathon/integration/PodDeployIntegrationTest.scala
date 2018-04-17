package mesosphere.marathon
package integration

import akka.http.scaladsl.client.RequestBuilding.Get
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.core.pod.{ HostNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.integration.setup.{ AkkaHttpResponse, EmbeddedMarathonTest, MesosConfig }
import mesosphere.marathon.state._
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

@IntegrationTest
class PodDeployIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Eventually {

  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    isolation = Some("filesystem/linux,docker/runtime"),
    imageProviders = Some("docker"))

  "Marathon" should {
    "deploy a resident pod" in {

      Given("a resident pod definition")
      val resident_pod = residentPodWithService("resident-pod")

      When("the pod is deployed")
      marathon.createPodV2(resident_pod) should be(Created)

      Then("Then the pod should become stable")
      val (resident_pod_port, resident_pod_address) = eventually {
        val status = marathon.status(resident_pod.id)
        status should be(Stable)
        status.value.instances(0).containers(0).endpoints(0).allocatedHostPort should be('defined)
        val port = status.value.instances(0).containers(0).endpoints(0).allocatedHostPort.get
        (port, status.value.instances(0).networks(0).addresses(0))
      }

      And("the service returns its content")
      implicit val requestTimeout = 30.seconds
      eventually {
        AkkaHttpResponse.request(Get(s"http://$resident_pod_address:$resident_pod_port/pst1/foo")).futureValue.entityString should be("start resident-pod\n")
      }

      marathonServer.close()
    }
  }

  /**
    * Define a Pod with a persistent volume and a simple http service.
    *
    * @param id Id of the pod run spec.
    * @return
    */
  def residentPodWithService(id: String) = {
    val projectDir = sys.props.getOrElse("user.dir", ".")

    val now = Timestamp.now()
    val cmd = s"cd $$MESOS_SANDBOX && echo 'start $id' >> pst1/foo && strace -TtttfFvx -s 256 -v -o pst1/$id-$now.trace src/app_mock.py $$ENDPOINT_TASK1 $id $now http://www.example.com"
    PodDefinition(
      id = testBasePath / id,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand(cmd))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
          volumeMounts = Seq(
            VolumeMount(Some("appmocksrc"), "src", true),
            VolumeMount(Some("pst"), "pst1", true)
          )
        )
      ),
      volumes = Seq(
        HostVolume(Some("appmocksrc"), s"$projectDir/src/test/python"),
        PersistentVolume(name = Some("pst"), persistent = PersistentVolumeInfo(size = 10L))
      ),
      networks = Seq(HostNetwork),
      instances = 1,
      unreachableStrategy = state.UnreachableDisabled,
      upgradeStrategy = state.UpgradeStrategy(0.0, 0.0)
    )
  }
}
