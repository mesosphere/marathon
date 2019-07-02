package mesosphere.marathon
package api.v2.validation

import com.wix.accord.{Failure, Result, Validator}
import mesosphere.marathon.raml.{Constraint, ConstraintOperator, DockerPullConfig, Endpoint, EnvVarSecret, Image, ImageType, Network, NetworkMode, PersistentVolumeInfo, Pod, PodContainer, PodEphemeralVolume, PodPersistentVolume, PodSchedulingPolicy, PodSecretVolume, PodUpgradeStrategy, Resources, SecretDef, UnreachableDisabled, UnreachableEnabled, VolumeMount}
import mesosphere.marathon.state.PersistentVolume
import mesosphere.marathon.util.SemanticVersion
import mesosphere.{UnitTest, ValidationTestLike}

class PodsValidationTest extends UnitTest with ValidationTestLike with PodsValidation with SchedulingValidation {

  "A pod definition" should {

    "be rejected if the id is empty" in new Fixture() {
      validator(validPod.copy(id = "/")) should haveViolations("/id" -> "Path must contain at least one path element")
    }

    "be rejected if the id is not absolute" in new Fixture() {
      validator(validPod.copy(id = "some/foo")) should haveViolations("/id" -> "Path needs to be absolute")
    }

    "be rejected if a defined user is empty" in new Fixture() {
      validator(validPod.copy(user = Some(""))) should haveViolations("/user" -> "must not be empty")
    }

    "be accepted if secrets defined" in new Fixture(validateSecrets = true) {
      private val valid = validPod.copy(secrets = Map("secret1" -> SecretDef(source = "/foo")), environment = Map("TEST" -> EnvVarSecret("secret1")))
      validator(valid) should be(aSuccess)
    }

    "be rejected if no container is defined" in new Fixture() {
      validator(validPod.copy(containers = Seq.empty)) should haveViolations("/containers" -> "must not be empty")
    }

    "be rejected if container names are not unique" in new Fixture() {
      validator(validPod.copy(containers = Seq(validContainer, validContainer))) should haveViolations(
        "/containers" -> PodsValidationMessages.ContainerNamesMustBeUnique)
    }

    "be rejected if endpoint names are not unique" in new Fixture() {
      val endpoint1 = Endpoint("endpoint", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint", hostPort = Some(124))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      validator(invalid) should haveViolations(
        "/" -> PodsValidationMessages.EndpointNamesMustBeUnique)
    }

    "be rejected if endpoint host ports are not unique" in new Fixture() {
      val endpoint1 = Endpoint("endpoint1", hostPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", hostPort = Some(123))
      private val invalid = validPod.copy(containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2))))
      validator(invalid) should haveViolations(
        "/" -> PodsValidationMessages.HostPortsMustBeUnique)
    }

    "be rejected if endpoint container ports are not unique" in new Fixture() {
      val endpoint1 = Endpoint("endpoint1", containerPort = Some(123))
      val endpoint2 = Endpoint("endpoint2", containerPort = Some(123))
      private val invalid = validPod.copy(
        networks = Seq(Network(mode = NetworkMode.Container, name = Some("default-network-name"))),
        containers = Seq(validContainer.copy(endpoints = Seq(endpoint1, endpoint2)))
      )
      validator(invalid) should haveViolations(
        "/" -> PodsValidationMessages.ContainerPortsMustBeUnique)
    }

    "be rejected if volume names are not unique" in new Fixture() {
      val volume = PodEphemeralVolume("volume")
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume, volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      validator(invalid) should haveViolations(
        "/volumes" -> PodsValidationMessages.VolumeNamesMustBeUnique)
    }

    "be rejected if a secret volume is defined without a corresponding secret" in new Fixture(validateSecrets = true) {
      val volume = PodSecretVolume("volume", "foo")
      val volumeMount = VolumeMount(volume.name, "/bla")
      private val invalid = validPod.copy(
        volumes = Seq(volume),
        containers = Seq(validContainer.copy(volumeMounts = Seq(volumeMount)))
      )
      // Here and below: stringifying validation is admittedly not the best way but it's a nested Set(GroupViolation...) and not easy to test.
      validator(invalid).toString should include(PodsValidationMessages.SecretVolumeMustReferenceSecret)
    }

    "be accepted if it is a valid pod with a Docker pull config" in new Fixture(validateSecrets = true) {
      validator(pullConfigPod) should be(aSuccess)
    }

    "be rejected if a pull config pod is provided when secrets features is disabled" in new Fixture(validateSecrets = false) {
      val validationResult: Result = validator(pullConfigPod)
      validationResult shouldBe a[Failure]
      val validationResultString: String = validationResult.toString
      validationResultString should include("must be empty")
      validationResultString should include("Feature secrets is not enabled. Enable with --enable_features secrets)")
    }

    "be rejected if a pull config pod doesn't have secrets" in new Fixture(validateSecrets = true) {
      private val invalid = pullConfigPod.copy(secrets = Map.empty)
      validator(invalid) should haveViolations(
        "/containers(0)/image/pullConfig" -> "pullConfig.secret must refer to an existing secret")
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

  "with persistent volumes" should {
    "be valid" in new Fixture {
      val pod = validResidentPod
      validator(pod) should be(aSuccess)
    }

    "be valid if no unreachable strategy is provided" in new Fixture {
      val pod = validResidentPod.copy(scheduling = validResidentPod.scheduling.map(_.copy(
        unreachableStrategy = None)))
      validator(pod) should be(aSuccess)
    }

    "be valid if no upgrade strategy is provided" in new Fixture {
      val pod = validResidentPod.copy(scheduling = validResidentPod.scheduling.map(_.copy(
        upgrade = None)))
      validator(pod) should be(aSuccess)
    }

    "be invalid if unreachable strategy is enabled" in new Fixture {
      val pod = validResidentPod.copy(scheduling = validResidentPod.scheduling.map(_.copy(
        unreachableStrategy = Some(UnreachableEnabled()))))
      validator(pod) should haveViolations(
        "/" -> "unreachableStrategy must be disabled for pods with persistent volumes")
    }

    "be invalid if upgrade strategy has maximumOverCapacity set to non-zero" in new Fixture {
      val pod = validResidentPod.copy(scheduling = validResidentPod.scheduling.map(_.copy(
        upgrade = Some(PodUpgradeStrategy(maximumOverCapacity = 0.1)))))
      validator(pod) should haveViolations(
        "/upgrade/maximumOverCapacity" -> "got 0.1, expected 0.0")
    }

    "be invalid if cpu changes" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(containers = pod.containers.map(ct => ct.copy(resources = ct.resources.copy(cpus = 3))))
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/" -> PodsValidationMessages.CpusPersistentVolumes)
    }

    "be invalid if mem changes" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(containers = pod.containers.map(ct => ct.copy(resources = ct.resources.copy(mem = 3))))
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/" -> PodsValidationMessages.MemPersistentVolumes)
    }

    "be invalid if disk changes" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(containers = pod.containers.map(ct => ct.copy(resources = ct.resources.copy(disk = 3))))
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/" -> PodsValidationMessages.DiskPersistentVolumes)
    }

    "be invalid if gpus change" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(containers = pod.containers.map(ct => ct.copy(resources = ct.resources.copy(gpus = 3))))
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/" -> PodsValidationMessages.GpusPersistentVolumes)
    }

    "be invalid with default upgrade strategy" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(upgradeStrategy = state.UpgradeStrategy.empty)
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/upgradeStrategy/maximumOverCapacity" -> "got 1.0, expected 0.0")
    }

    "be invalid if persistent volumes change" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to = pod.copy(volumes = pod.volumes.map {
        case vol: PersistentVolume => vol.copy(persistent = vol.persistent.copy(size = 2))
        case vol => vol
      })
      residentUpdateIsValid(pod)(to) should haveViolations(
        "/" -> "persistent volumes cannot be updated")
    }

    "be invalid if ports change" in new Fixture {
      val pod = validResidentPod.fromRaml
      val to1 = pod.copy(containers = pod.containers.map(ct => ct.copy(
        endpoints = ct.endpoints.map(ep => ep.copy(hostPort = None)))))
      val to2 = pod.copy(containers = pod.containers.map(ct => ct.copy(
        endpoints = ct.endpoints.map(ep => ep.copy(hostPort = Some(2))))))
      residentUpdateIsValid(pod)(to1) should haveViolations(
        "/" -> PodsValidationMessages.HostPortsPersistentVolumes)
      residentUpdateIsValid(pod)(to2) should haveViolations(
        "/" -> PodsValidationMessages.HostPortsPersistentVolumes)
    }
  }

  class Fixture(validateSecrets: Boolean = false) {
    val validContainer = PodContainer(
      name = "ct1",
      resources = Resources()
    )
    val validPod = Pod(
      id = "/some/pod",
      containers = Seq(validContainer),
      networks = Seq(Network(mode = NetworkMode.Host))
    )

    val pullConfigContainer = PodContainer(
      name = "pull-config-container",
      resources = Resources(),
      image = Some(Image(kind = ImageType.Docker, id = "some/image", pullConfig = Some(DockerPullConfig("aSecret"))))
    )
    val pullConfigPod = Pod(
      id = "/pull/config/pod",
      containers = Seq(pullConfigContainer),
      secrets = Map("aSecret" -> SecretDef("/pull/config"))
    )

    def validResidentPod = validPod.copy(
      containers = Seq(validContainer.copy(
        endpoints = Seq(Endpoint("ep1", hostPort = Some(1))),
        volumeMounts = Seq(VolumeMount("vol1", "vol1-mount", Some(false))))),
      volumes = Seq(PodPersistentVolume("vol1", PersistentVolumeInfo(size = 1))),
      scheduling = Some(PodSchedulingPolicy(
        upgrade = Some(PodUpgradeStrategy(minimumHealthCapacity = 0, maximumOverCapacity = 0)),
        unreachableStrategy = Some(UnreachableDisabled()))))

    val features: Set[String] = if (validateSecrets) Set(Features.SECRETS) else Set.empty
    implicit val validator: Validator[Pod] = podValidator(features, SemanticVersion.zero, None)
  }

  "network validation" when {
    implicit val validator: Validator[Pod] = podValidator(Set.empty, SemanticVersion.zero, Some("default-network-name"))

    def podContainer(name: String = "ct1", resources: Resources = Resources(), endpoints: Seq[Endpoint]) =
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
        validator(app) should be(aSuccess)
      }

      "allow endpoints for pods with bridge networking" in {
        val pod = Pod(
          id = "/bridge",
          networks = Seq(Network(mode = NetworkMode.ContainerBridge)),
          containers = Seq(podContainer(endpoints = Seq(Endpoint("endpoint", hostPort = Some(0), containerPort = Some(80)))))
        )

        validator(pod) should be(aSuccess)
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
        validator(app) should be(aSuccess)
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
                  networkNames = Nil)))), networks)) should be(aSuccess)
        }

        s"${subtitle} allow endpoint without hostport" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "endpoint",
                  hostPort = None,
                  containerPort = Some(80),
                  networkNames = Nil)))), networks)) should be(aSuccess)
        }

        s"${subtitle} allow endpoint with zero hostport" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint(
                  "endpoint",
                  containerPort = Some(80),
                  hostPort = Some(0))))), networks)) should be(aSuccess)
        }

        s"${subtitle} allows containerPort of zero" in {
          validator(
            networkedPod(Seq(
              podContainer(endpoints = Seq(
                Endpoint("name1", containerPort = Some(0)),
                Endpoint("name2", containerPort = Some(0))
              ))), networks)) should be(aSuccess)
        }

        s"${subtitle} require that hostPort is unique" in {
          val pod = networkedPod(Seq(
            podContainer(endpoints = Seq(
              Endpoint(
                "name1",
                hostPort = Some(123)),
              Endpoint(
                "name2",
                hostPort = Some(123))))), networks)
          validator(pod) should haveViolations("/" -> PodsValidationMessages.HostPortsMustBeUnique)
        }
      }

      behave like containerAndBridgeMode("container-mode:", networks(1))
      behave like containerAndBridgeMode("bridge-mode:", bridgeNetwork)
    }

    "container-mode: requires containerPort" in {
      val pod = networkedPod(Seq(
        podContainer(endpoints = Seq(
          Endpoint(
            "name1",
            hostPort = Some(123))))))
      validator(pod) should haveViolations(
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
              networkNames = List("1"))))))) should be(aSuccess)
    }

    "disallow endpoint with a host port and two valid networkNames" in {
      validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              hostPort = Some(80),
              containerPort = Some(80),
              networkNames = List("1", "2"))))))) shouldNot be(aSuccess)
    }

    "disallow endpoint with a non-matching network name" in {
      validator(
        networkedPod(Seq(
          podContainer(endpoints = Seq(
            Endpoint(
              "endpoint",
              containerPort = Some(80),
              hostPort = Some(80),
              networkNames = List("invalid-network-name"))))))) shouldNot be(aSuccess)
    }

    "allow endpoint without hostPort for host networking" in {
      validator(networkedPod(Seq(
        podContainer(endpoints = Seq(Endpoint("ep")))
      ), hostNetwork)) should be(aSuccess)
    }
  }
}
