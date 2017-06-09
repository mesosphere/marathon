package mesosphere.marathon
package api.v2.validation

import com.wix.accord.scalatest.ResultMatchers
import mesosphere.marathon.raml._
import mesosphere.{ UnitTest, ValidationTestLike }

class AppValidationTest extends UnitTest with ResultMatchers with ValidationTestLike {

  import Normalization._

  "File based secrets validation" when {
    implicit val basicValidator = AppValidation.validateCanonicalAppAPI(Set.empty)
    implicit val withSecretsValidator = AppValidation.validateCanonicalAppAPI(Set("secrets"))

    "file based secret is used when secret feature is not enabled" should {
      "fail" in {
        val app = App(id = "/app", cmd = Some("cmd"),
          container = Option(raml.Container(`type` = EngineType.Mesos, volumes = Seq(AppSecretVolume("/path", "bar"))))
        )
        val validation = basicValidator(app)
        validation.isFailure shouldBe true
        // Here and below: stringifying validation is admittedly not the best way but it's a nested Set(GroupViolation...) and not easy to test.
        validation.toString should include ("Feature secrets is not enabled. Enable with --enable_features secrets")
      }
    }

    "file based secret is used when corresponding secret is missing" should {
      "fail" in {
        val app = App(id = "/app", cmd = Some("cmd"),
          secrets = Map[String, SecretDef]("foo" -> SecretDef("/bar")),
          container = Option(raml.Container(`type` = EngineType.Mesos, volumes = Seq(AppSecretVolume("/path", "baz"))))
        )
        val validation = withSecretsValidator(app)
        validation.isFailure shouldBe true
        validation.toString should include ("volume.secret must refer to an existing secret")
      }
    }

    "a valid file based secret" should {
      "succeed" in {
        val app = App(id = "/app", cmd = Some("cmd"),
          secrets = Map[String, SecretDef]("foo" -> SecretDef("/bar")),
          container = Option(raml.Container(`type` = EngineType.Mesos, volumes = Seq(AppSecretVolume("/path", "foo"))))
        )
        withSecretsValidator(app) shouldBe (aSuccess)
      }
    }
  }

  "Docker image pull config validation" when {
    implicit val basicValidator = AppValidation.validateCanonicalAppAPI(Set("secrets"))

    "pull config when the Mesos containerizer is used and the corresponding secret is provided" should {
      "be accepted" in {
        val app = App(
          id = "/foo",
          cmd = Some("bar"),
          container = Some(Container(
            `type` = EngineType.Mesos,
            docker = Some(DockerContainer(
              image = "xyz", pullConfig = Some(DockerPullConfig("aSecret")))))),
          secrets = Map("aSecret" -> SecretDef("/secret")))

        basicValidator(app) shouldBe (aSuccess)
      }
    }

    "pull config when the Mesos containerizer is used and the corresponding secret is not provided" should {
      "fail" in {
        val app = App(
          id = "/foo",
          cmd = Some("bar"),
          container = Some(Container(
            `type` = EngineType.Mesos,
            docker = Some(DockerContainer(
              image = "xyz", pullConfig = Some(DockerPullConfig("aSecret")))))))

        basicValidator(app).normalize should failWith(
          "/container/docker/pullConfig" ->
            "pullConfig.secret must refer to an existing secret")
      }
    }

    "pull config when the Docker containerizer is used and the corresponding secret is provided" should {
      "fail" in {
        val app = App(
          id = "/foo",
          cmd = Some("bar"),
          container = Some(Container(
            `type` = EngineType.Docker,
            docker = Some(DockerContainer(
              image = "xyz", pullConfig = Some(DockerPullConfig("aSecret")))))),
          secrets = Map("aSecret" -> SecretDef("/secret")))

        basicValidator(app).normalize should failWith(
          "/container/docker" ->
            "pullConfig is not supported with Docker containerizer")
      }
    }
  }

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
