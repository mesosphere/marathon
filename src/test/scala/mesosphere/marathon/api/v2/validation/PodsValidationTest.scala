package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.scalatest.ResultMatchers
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml.{ Constraint, ConstraintOperator, Endpoint, EnvVarSecret, EphemeralVolume, Network, NetworkMode, Pod, PodContainer, PodSecretVolume, Resources, SecretDef, VolumeMount }
import mesosphere.marathon.util.SemanticVersion

class PodsValidationTest extends UnitTest with ResultMatchers with PodsValidation with SchedulingValidation with ValidationTestLike {

  "A pod definition" should {

    "be rejected if the id is empty" in new Fixture {
      private val invalid = validPod.copy(id = "/")
      validator(invalid) should failWith("id" -> "Path must contain at least one path element")
    }

    "be rejected if the id is not absolute" in new Fixture {
      private val invalid = validPod.copy(id = "some/foo")
      validator(invalid) should failWith("id" -> "Path needs to be absolute")
    }

    "be rejected if a defined user is empty" in new Fixture {
      private val invalid = validPod.copy(user = Some(""))
      validator(invalid) should failWith("user" -> "must not be empty")
    }

    "be accepted if secrets defined" in new Fixture {
      val secretValidator: Validator[Pod] = podValidator(Set(Features.SECRETS), SemanticVersion.zero)
      private val valid = validPod.copy(secrets = Map("secret1" -> SecretDef(source = "/foo")), environment = Map("TEST" -> EnvVarSecret("secret1")))
      secretValidator(valid) shouldBe aSuccess
    }

    "be rejected if no container is defined" in new Fixture {
      private val invalid = validPod.copy(containers = Seq.empty)
      validator(invalid) should failWith("containers" -> "must not be empty")
    }

    "be rejected if container names are not unique" in new Fixture {
      private val invalid = validPod.copy(containers = Seq(validContainer, validContainer))
      validator(invalid) should failWith("containers" -> PodsValidationMessages.ContainerNamesMustBeUnique)
    }

    "be rejected if endpoint names are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint", hostPort = Some(124))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      validator(invalid) should failWith("value" -> PodsValidationMessages.EndpointNamesMustBeUnique)
    }

    "be rejected if endpoint host ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", hostPort = Some(123))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      validator(invalid) should failWith("value" -> PodsValidationMessages.HostPortsMustBeUnique)
    }

    "be rejected if endpoint container ports are not unique" in new Fixture {
      val endpoint1 = Endpoint("endpoint1", containerPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", containerPort = Some(123))
      private val invalid = validPod.copy(
        networks = Seq(Network(mode = NetworkMode.Container)),
        containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2)))
      )
      validator(invalid) should failWith("value" -> PodsValidationMessages.ContainerPortsMustBeUnique)
    }

    "be rejected if volume names are not unique" in new Fixture {
      val volume = EphemeralVolume("volume")
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume, volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      validator(invalid) should failWith("volumes" -> PodsValidationMessages.VolumeNamesMustBeUnique)
    }

    "be rejected if a secret volume is defined without a corresponding secret" in new Fixture {
      val volume = PodSecretVolume("volume", "foo")
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      // Here and below: stringifying validation is admittedly not the best way but it's a nested Set(GroupViolation...) and not easy to test.
      validator(invalid).toString should include(PodsValidationMessages.SecretVolumeMustReferenceSecret)
    }
  }

  "A constraint definition" should {

    "MaxPer is accepted with an integer value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer, Some("3"))).isSuccess shouldBe true
    }

    "MaxPer is rejected with no value" in {
      complyWithConstraintRules(Constraint("foo", ConstraintOperator.MaxPer)).isSuccess shouldBe false
    }
  }

  class Fixture {
    val validContainer = PodContainer(
      name = "ct1",
      resources = Resources()
    )
    val validPod = Pod(
      id = "/some/pod",
      containers = Seq(validContainer),
      networks = Seq(Network(mode = NetworkMode.Host))
    )
    val validator: Validator[Pod] = podValidator(Set.empty, SemanticVersion.zero)
  }

  "network validation" when {
    val validator: Validator[Pod] = podValidator(Set.empty, SemanticVersion.zero)

    def podContainer(name: String = "ct1", resources: Resources = Resources(), endpoints: Seq[Endpoint] = Nil) =
      PodContainer(
        name = name,
        resources = resources,
        endpoints = endpoints)

    def networks(networkCount: Int = 1): Seq[Network] =
      1.to(networkCount).map(i => Network(mode = NetworkMode.Container, name = Some(i.toString)))

    def bridgeNetwork: Seq[Network] = Seq(Network(mode = NetworkMode.ContainerBridge))

    def hostNetwork: Seq[Network] = Seq(Network(mode = NetworkMode.Host))

    def networkedPod(containers: Seq[PodContainer], nets: => Seq[Network] = networks()) =
      Pod(
        id = "/foo",
        networks = nets,
        containers = containers)

    "multiple container networks are specified for a pod" should {

      "require networkNames for containerPort to hostPort mapping" in {
        val badApp = networkedPod(
          Seq(podContainer(endpoints = Seq(Endpoint("endpoint", containerPort = Some(80), hostPort = Option(0))))),
          networks(2))

        validator(badApp).isFailure shouldBe true
      }

      "allow endpoints that don't declare hostPort nor networkNames" in {
        val app = networkedPod(
          Seq(podContainer(endpoints = Seq(Endpoint("endpoint", containerPort = Some(80))))),
          networks(2))
        validator(app) shouldBe (aSuccess)
      }

      "allow endpoints for pods with bridge networking" in {
        val pod = Pod(
          id = "/bridge",
          networks = Seq(Network(mode = NetworkMode.ContainerBridge)),
          containers = Seq(podContainer(endpoints = Seq(Endpoint("endpoint", hostPort = Some(0), containerPort = Some(80)))))
        )

        validator(pod) shouldBe (aSuccess)
      }

      "allow endpoints that both declare a hostPort and a networkNames" in {
        val app = networkedPod(
          Seq(podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Option(0),
              containerPort = Some(80),
              networkNames = List("1"))))),
          networks(2))
        validator(app) shouldBe (aSuccess)
      }
    }

    "bridge or single container network" should {

      def containerAndBridgeMode(subtitle: String, networks: => Seq[Network]): Unit = {
        s"${subtitle} allow endpoint with no networkNames" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "endpoint",
                  hostPort = Some(80),
                  containerPort = Some(80),
                  networkNames = Nil)))), networks)) shouldBe (aSuccess)
        }

        s"${subtitle} allow endpoint without hostport" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "endpoint",
                  hostPort = None,
                  containerPort = Some(80),
                  networkNames = Nil)))), networks)) shouldBe (aSuccess)
        }

        s"${subtitle} allow endpoint with zero hostport" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "endpoint",
                  containerPort = Some(80),
                  hostPort = Some(0))))), networks)) shouldBe (aSuccess)
        }

        s"${subtitle} allows containerPort of zero" in {
          val result = validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint("name1", containerPort = Some(0)),
                Endpoint("name2", containerPort = Some(0))
              ))), networks))

          result shouldBe aSuccess
        }

        s"${subtitle} require that hostPort is unique" in {
          val result = validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "name1",
                  hostPort = Some(123)),
                Endpoint(
                  "name2",
                  hostPort = Some(123))))), networks))

          result should containViolation(
            "/" -> PodsValidationMessages.HostPortsMustBeUnique)
        }
      }

      behave like containerAndBridgeMode("container-mode:", networks(1))
      behave like containerAndBridgeMode("bridge-mode:", bridgeNetwork)
    }

    "container-mode: requires containerPort" in {
      val result = validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "name1",
              hostPort = Some(123)))))))

      result should containViolation(
        "/containers(0)/endpoints(0)/containerPort" -> "is required when using container-mode networking")
    }

    "allow endpoint with a networkNames" in {
      validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = List("1"))))))) shouldBe (aSuccess)
    }

    "disallow endpoint with a host port and two valid networkNames" in {
      validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = List("1", "2"))))))) shouldBe (aFailure)
    }

    "disallow endpoint with a non-matching network name" in {
      val result = validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              containerPort = Some(80),
              hostPort = Some(80),
              networkNames = List("invalid-network-name")))))))
      result.isFailure shouldBe true
    }

    "allow endpoint without hostPort for host networking" in {
      val result = validator(networkedPod(Seq(
        podContainer(endpoints = Seq(Endpoint("ep")))
      ), hostNetwork))
      result shouldBe aSuccess
    }
  }
}
