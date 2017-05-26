package mesosphere.marathon
package api.v2.validation

import com.wix.accord.scalatest.ResultMatchers
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml._
import mesosphere.UnitTest

class AppValidationTest extends UnitTest with ResultMatchers with ValidationTestLike {

  import Normalization._

  "network validation" when {
    implicit val basicValidator = AppValidation.validateCanonicalAppAPI(Set.empty)

    def networkedApp(portMappings: Seq[ContainerPortMapping], networks: Seq[Network], docker: Boolean = false) = {
      App(
        id = "/foo",
        cmd = Some("bar"),
        networks = networks,
        container = Some(Container(
          portMappings = Some(portMappings),
          `type` = if (docker) EngineType.Docker else EngineType.Mesos,
          docker = if (docker) Some(DockerContainer(image = "foo")) else None
        )))
    }

    def containerNetworkedApp(portMappings: Seq[ContainerPortMapping], networkCount: Int = 1, docker: Boolean = false) =
      networkedApp(
        portMappings,
        networks = 1.to(networkCount).map { i => Network(mode = NetworkMode.Container, name = Some(i.toString)) },
        docker = docker)

    "multiple container networks are specified for an app" should {

      "require networkNames for hostPort to containerPort mapping" in {
        val badApp = containerNetworkedApp(
          Seq(ContainerPortMapping(hostPort = Option(0))), networkCount = 2)

        basicValidator(badApp).normalize should failWith(
          "/container/portMappings(0)" ->
            AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks)
      }

      "limit docker containers to a single network" in {
        val app = containerNetworkedApp(
          Seq(ContainerPortMapping()), networkCount = 2, true)
        basicValidator(app).normalize should failWith(
          "/" -> AppValidationMessages.DockerEngineLimitedToSingleContainerNetwork
        )
      }

      "allow portMappings that don't declare hostPort nor networkNames" in {
        val app = containerNetworkedApp(
          Seq(ContainerPortMapping()), networkCount = 2)
        basicValidator(app) shouldBe (aSuccess)
      }

      "allow portMappings that both declare a hostPort and a networkNames" in {
        val app = containerNetworkedApp(Seq(
          ContainerPortMapping(
            hostPort = Option(0),
            networkNames = List("1"))), networkCount = 2)
        basicValidator(app) shouldBe (aSuccess)
      }
    }

    "single container network" should {

      "consider a valid portMapping with a name as valid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkNames = List("1"))))) shouldBe (aSuccess)
      }

      "consider a portMapping with a hostPort and two valid networkNames as invalid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkNames = List("1", "2"))),
            networkCount = 3)) should containViolation(
            "/container/portMappings(0)" -> AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks)
      }

      "consider a portMapping with no name as valid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = Some(80),
                containerPort = 80,
                networkNames = Nil)))) shouldBe (aSuccess)
      }

      "consider a portMapping without a hostport as valid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                hostPort = None)))) shouldBe (aSuccess)
      }

      "consider portMapping with zero hostport as valid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(0))))) shouldBe (aSuccess)
      }

      "consider portMapping with a non-matching network name as invalid" in {
        val result = basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = Some(80),
                networkNames = List("undefined-network-name")))))
        result.isFailure shouldBe true
      }

      "consider portMapping without networkNames nor hostPort as valid" in {
        basicValidator(
          containerNetworkedApp(
            Seq(
              ContainerPortMapping(
                containerPort = 80,
                hostPort = None,
                networkNames = Nil)))) shouldBe (aSuccess)
      }
    }

    "general port validation" in {
      basicValidator(
        containerNetworkedApp(
          Seq(
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123)),
            ContainerPortMapping(
              name = Some("name"),
              hostPort = Some(123))))).isFailure shouldBe true
    }

    "missing hostPort is allowed for bridge networking (so we can normalize it)" in {
      // This isn't _actually_ allowed; we expect that normalization will replace the None to a Some(0) before
      // converting to an AppDefinition, in order to support legacy API
      val app = networkedApp(
        portMappings = Seq(ContainerPortMapping(
          containerPort = 8080,
          hostPort = None,
          servicePort = 0,
          name = Some("foo"))),
        networks = Seq(Network(mode = NetworkMode.ContainerBridge, name = None))
      )
      basicValidator(app) shouldBe (aSuccess)
    }

  }
}
