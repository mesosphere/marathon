package mesosphere.marathon
package api.v2

import java.time.OffsetDateTime

import javax.servlet.http.HttpServletResponse
import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
import mesosphere.marathon.api.v2.validation.NetworkValidationMessages
import mesosphere.marathon.api.{JsonTestHelper, RestResource, TaskKiller, TestAuthFixture}
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition, PodManager}
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer}
import mesosphere.marathon.raml.{EnvVarSecret, ExecutorResources, FixedPodScalingPolicy, NetworkMode, PersistentVolumeInfo, PersistentVolumeType, Pod, PodPersistentVolume, PodSecretVolume, PodState, PodStatus, Raml, Resources, VolumeMount}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp, UnreachableStrategy, VersionInfo}
import mesosphere.marathon.test.{JerseyTest, Mockito, SettableClock}
import mesosphere.marathon.util.SemanticVersion
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class PodsResourceTest extends AkkaUnitTest with Mockito with JerseyTest {

  // TODO(jdef) incorporate checks for firing pod events on C, U, D operations

  val podSpecJson = """
                   | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                   |   { "name": "webapp",
                   |     "resources": { "cpus": 0.03, "mem": 64 },
                   |     "image": { "kind": "DOCKER", "id": "busybox" },
                   |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                 """.stripMargin

  val podSpecJsonWithBridgeNetwork = """
                   | { "id": "/mypod", "networks": [ { "mode": "container/bridge" } ], "containers": [
                   |   { "name": "webapp",
                   |     "resources": { "cpus": 0.03, "mem": 64 },
                   |     "image": { "kind": "DOCKER", "id": "busybox" },
                   |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                 """.stripMargin

  val podSpecJsonWithContainerNetworking = """
                   | { "id": "/mypod", "networks": [ { "mode": "container" } ], "containers": [
                   |   { "name": "webapp",
                   |     "resources": { "cpus": 0.03, "mem": 64 },
                   |     "image": { "kind": "DOCKER", "id": "busybox" },
                   |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                 """.stripMargin

  val podSpecJsonWithExecutorResources = """
                      | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                      |   { "name": "webapp",
                      |     "resources": { "cpus": 0.03, "mem": 64 },
                      |     "image": { "kind": "DOCKER", "id": "busybox" },
                      |     "exec": { "command": { "shell": "sleep 1" } } } ],
                      |     "executorResources": { "cpus": 100, "mem": 100 } }
                    """.stripMargin

  val podSpecJsonWithFileBasedSecret = """
                      | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                      |   [
                      |     { "name": "webapp",
                      |       "resources": { "cpus": 0.03, "mem": 64 },
                      |       "image": { "kind": "DOCKER", "id": "busybox" },
                      |       "exec": { "command": { "shell": "sleep 1" } },
                      |       "volumeMounts": [ { "name": "vol", "mountPath": "mnt2" } ]
                      |     }
                      |   ],
                      |   "volumes": [ { "name": "vol", "secret": "secret1" } ],
                      |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                      |  }
                    """.stripMargin

  val podSpecJsonWithEnvRefSecretOnContainerLevel = """
                      | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                      |   [
                      |     { "name": "webapp",
                      |       "resources": { "cpus": 0.03, "mem": 64 },
                      |       "image": { "kind": "DOCKER", "id": "busybox" },
                      |       "exec": { "command": { "shell": "sleep 1" } },
                      |       "environment": { "vol": { "secret": "secret1" } }
                      |     }
                      |   ],
                      |   "secrets": { "secret1": { "source": "/path/to/my/secret" } }
                      |  }
                    """.stripMargin

  val podSpecJsonWithEnvRefSecret = """
                      | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers":
                      |   [
                      |     { "name": "webapp",
                      |       "resources": { "cpus": 0.03, "mem": 64 },
                      |       "image": { "kind": "DOCKER", "id": "busybox" },
                      |       "exec": { "command": { "shell": "sleep 1" } }
                      |     }
                      |   ],
                      |   "environment": { "vol": { "secret": "secret1" } },
                      |   "secrets": { "secret1": { "source": "/foo" } }
                      |  }
                    """.stripMargin

  "PodsResource" should {
    "support pods" in {
      val f = Fixture()
      val response = f.podsResource.capability(f.auth.request)
      response.getStatus should be(HttpServletResponse.SC_OK)

      val body = Option(response.getEntity.asInstanceOf[String])
      body should be(None)
    }

    "be able to create a simple single-container pod from docker image w/ shell command" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.networks(0).mode should be (NetworkMode.Host)
        pod.networks(0).name should not be (defined)
        pod.executorResources should be (defined) // validate that executor resources are defined
        pod.executorResources.get should be (ExecutorResources()) // validate that the executor resources has default values

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "be able to create a simple single-container pod with bridge network" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithBridgeNetwork.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.networks(0).mode should be (NetworkMode.ContainerBridge)
        pod.networks(0).name should not be (defined)
        pod.executorResources should be (defined) // validate that executor resources are defined
        pod.executorResources.get should be (ExecutorResources()) // validate that the executor resources has default values

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses file base secrets) fails" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs) fails" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithEnvRefSecret.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs on container level) fails" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is enabled and create pod (that uses env secret refs on container level) succeeds" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(201)
        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.containers(0).environment("vol") shouldBe EnvVarSecret("secret1")
      }
    }

    "The secrets feature is enabled and create pod (that uses file based secrets) succeeds" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(201)
        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.volumes(0) shouldBe PodSecretVolume("vol", "secret1")
      }
    }

    "create a pod w/ container networking" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // required since network name is missing from JSON

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.networks(0).mode should be (NetworkMode.Container)
        pod.networks(0).name should be (Some("blah"))
        pod.executorResources should be (defined) // validate that executor resources are defined
        pod.executorResources.get should be (ExecutorResources()) // validate that the executor resources has default values

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "create a pod w/ container networking w/o default network name" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request, r)
      }
      response.getStatus shouldBe 422
      response.getEntity.toString should include(NetworkValidationMessages.NetworkNameMustBeSpecified)
    }

    "create a pod with custom executor resource declaration" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = asyncRequest { r =>
        f.podsResource.create(podSpecJsonWithExecutorResources.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.executorResources should be (defined) // validate that executor resources are defined
        pod.executorResources.get.cpus should be (100)
        pod.executorResources.get.mem should be (100)
        // disk is not assigned in the posted pod definition, therefore this should be the default value 10
        pod.executorResources.get.disk should be (10)

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "update a simple single-container pod from docker image w/ shell command" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val postJson = """
                       | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                       |   { "name": "webapp",
                       |     "resources": { "cpus": 0.03, "mem": 64 },
                       |     "image": { "kind": "DOCKER", "id": "busybox" },
                       |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                     """.stripMargin
      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", postJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should not be None
        parsedResponse.map(_.as[Pod]) should not be None // validate that we DID get back a pod definition

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "save pod with more than one instance" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val postJson = """
                       | { "id": "/mypod", "networks": [ { "mode": "host" } ],
                       | "scaling": { "kind": "fixed", "instances": 2 }, "containers": [
                       |   { "name": "webapp",
                       |     "resources": { "cpus": 0.03, "mem": 64 },
                       |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                     """.stripMargin
      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", postJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should not be None
        val podOption = parsedResponse.map(_.as[Pod])
        podOption should not be None // validate that we DID get back a pod definition

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
        podOption.get.scaling should not be None
        podOption.get.scaling.get shouldBe a[FixedPodScalingPolicy]
        podOption.get.scaling.get.asInstanceOf[FixedPodScalingPolicy].instances should be (2)
      }
    }

    "create a pod with a persistent volume" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podSpecJsonWithPersistentVolume =
        """
          | { "id": "/mypod",
          |   "containers": [ {
          |     "name": "dataapp",
          |     "resources": { "cpus": 0.03, "mem": 64 },
          |     "image": { "kind": "DOCKER", "id": "busybox" },
          |     "exec": { "command": { "shell": "sleep 1" } },
          |     "volumeMounts": [ { "name": "pst", "mountPath": "pst1", "readOnly": false } ]
          |   } ],
          |   "volumes": [ {
          |     "name": "pst",
          |     "persistent": { "type": "root", "size": 10 }
          |   } ] }
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", podSpecJsonWithPersistentVolume.getBytes(), force = false, f.auth.request, r)
      }
      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val jsonResponse = Json.parse(response.getEntity.asInstanceOf[String])
        val pod = jsonResponse.as[Pod]
        val volumeInfo = PersistentVolumeInfo(`type` = Some(PersistentVolumeType.Root), size = 10)
        val volume = PodPersistentVolume(name = "pst", persistent = volumeInfo)
        val volumeMount = VolumeMount(name = "pst", mountPath = "pst1", readOnly = Some(false))
        pod.volumes.head shouldBe volume
        pod.containers.head.volumeMounts.head shouldBe volumeMount
      }
    }

    "fail to create a pod with a persistent volume if unreachable strategy is enabled" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podSpecJsonWithPersistentVolume =
        """
          | { "id": "/mypod",
          |   "scheduling": { "unreachableStrategy": {} },
          |   "containers": [ {
          |     "name": "dataapp",
          |     "resources": { "cpus": 0.03, "mem": 64 },
          |     "image": { "kind": "DOCKER", "id": "busybox" },
          |     "exec": { "command": { "shell": "sleep 1" } },
          |     "volumeMounts": [ { "name": "pst", "mountPath": "pst1", "readOnly": false } ]
          |   } ],
          |   "volumes": [ {
          |     "name": "pst",
          |     "persistent": { "type": "root", "size": 10 }
          |   } ] }
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", podSpecJsonWithPersistentVolume.getBytes(), force = false, f.auth.request, r)
      }
      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("unreachableStrategy must be disabled for pods with persistent volumes")
      }
    }

    "fail to create a pod with a persistent volume if upgrade.maximumOverCapacity != 0" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podSpecJsonWithPersistentVolume =
        """
          | { "id": "/mypod",
          |   "scheduling": { "upgrade": { "maximumOverCapacity": 0.1 } },
          |   "containers": [ {
          |     "name": "dataapp",
          |     "resources": { "cpus": 0.03, "mem": 64 },
          |     "image": { "kind": "DOCKER", "id": "busybox" },
          |     "exec": { "command": { "shell": "sleep 1" } },
          |     "volumeMounts": [ { "name": "pst", "mountPath": "pst1", "readOnly": false } ]
          |   } ],
          |   "volumes": [ {
          |     "name": "pst",
          |     "persistent": { "type": "root", "size": 10 }
          |   } ] }
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", podSpecJsonWithPersistentVolume.getBytes(), force = false, f.auth.request, r)
      }
      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("/upgrade/maximumOverCapacity")
        response.getEntity.toString should include("got 0.1, expected 0.0")
      }
    }

    "fail to create a pod with a persistent volume if acceptedResourceRoles != *" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podSpecJsonWithPersistentVolume =
        """
          | { "id": "/mypod",
          |   "scheduling": { "placement": { "acceptedResourceRoles": ["*", "slave_public"] } },
          |   "containers": [ {
          |     "name": "dataapp",
          |     "resources": { "cpus": 0.03, "mem": 64 },
          |     "image": { "kind": "DOCKER", "id": "busybox" },
          |     "exec": { "command": { "shell": "sleep 1" } },
          |     "volumeMounts": [ { "name": "pst", "mountPath": "pst1", "readOnly": false } ]
          |   } ],
          |   "volumes": [ {
          |     "name": "pst",
          |     "persistent": { "type": "root", "size": 10 }
          |   } ] }
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.update("/mypod", podSpecJsonWithPersistentVolume.getBytes(), force = false, f.auth.request, r)
      }
      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("/acceptedResourceRoles")
        response.getEntity.toString should include("""A resident pod must have `acceptedResourceRoles = [\"*\"]`.""")
      }
    }

    "delete a pod" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.find(any).returns(Some(PodDefinition()))
      podSystem.delete(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
      val response = asyncRequest { r =>
        f.podsResource.remove("/mypod", force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_ACCEPTED)

        val body = Option(response.getEntity.asInstanceOf[String])
        body should be(None)

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "lookup a specific pod, and that pod does not exist" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.find(any).returns(Option.empty[PodDefinition])
      val response = f.podsResource.find("/mypod", f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
        val body = Option(response.getEntity.asInstanceOf[String])
        body should not be None
        body.foreach(_ should include("mypod does not exist"))
      }
    }

    "find all pods" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.findAll(any).returns(List(PodDefinition(), PodDefinition()))
      val response = f.podsResource.findAll(f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val jsonBody = Json.parse(response.getEntity.asInstanceOf[String])
        jsonBody.asInstanceOf[JsArray].value.size shouldEqual 2
      }
    }

    "get pod status" in {
      implicit val podStatusService = mock[PodStatusService]
      val f = Fixture()

      podStatusService.selectPodStatus(any, any).returns(Future(Some(PodStatus("mypod", Pod("mypod", containers = Seq.empty), PodState.Stable, statusSince = OffsetDateTime.now(), lastUpdated = OffsetDateTime.now(), lastChanged = OffsetDateTime.now()))))

      val response = f.podsResource.status("/mypod", f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val jsonBody = Json.parse(response.getEntity.asInstanceOf[String])
        (jsonBody \ "id").get.asInstanceOf[JsString].value shouldEqual "mypod"
      }
    }

    "get all pod statuses" in {
      implicit val podStatusService = mock[PodStatusService]
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.ids().returns(Set(PathId("mypod")))
      podStatusService.selectPodStatus(any, any).returns(Future(Some(PodStatus("mypod", Pod("mypod", containers = Seq.empty), PodState.Stable, statusSince = OffsetDateTime.now(), lastUpdated = OffsetDateTime.now(), lastChanged = OffsetDateTime.now()))))

      val response = f.podsResource.allStatus(f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val jsonBody = Json.parse(response.getEntity.asInstanceOf[String])
        jsonBody.asInstanceOf[JsArray].value.size shouldEqual 1
      }
    }

    "Create a new pod with w/ Docker image and config.json" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--enable_features", "secrets"))

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podJson =
        """
          |{
          |    "id": "/pod",
          |    "containers": [{
          |        "name": "container0",
          |        "resources": {
          |            "cpus": 0.1,
          |            "mem": 32
          |        },
          |        "image": {
          |            "kind": "DOCKER",
          |            "id": "private/image",
          |            "pullConfig": {
          |                "secret": "pullConfigSecret"
          |            }
          |        },
          |        "exec": {
          |            "command": {
          |                "shell": "sleep 1"
          |            }
          |        }
          |    }],
          |    "secrets": {
          |        "pullConfigSecret": {
          |            "source": "/config"
          |        }
          |    }
          |}
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.create(podJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should be (defined)
        val maybePod = parsedResponse.map(_.as[Pod])
        maybePod should be (defined) // validate that we DID get back a pod definition
        val pod = maybePod.get
        pod.containers.headOption should be (defined)
        val container = pod.containers.head
        container.image should be (defined)
        val image = container.image.get
        image.pullConfig should be (defined)
        val pullConfig = image.pullConfig.get
        pullConfig.secret should be ("pullConfigSecret")

        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
      }
    }

    "Creating a new pod with w/ AppC image and config.json should fail" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--enable_features", "secrets"))

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podJson =
        """
          |{
          |    "id": "/pod",
          |    "containers": [{
          |        "name": "container0",
          |        "resources": {
          |            "cpus": 0.1,
          |            "mem": 32
          |        },
          |        "image": {
          |            "kind": "APPC",
          |            "id": "private/image",
          |            "pullConfig": {
          |                "secret": "pullConfigSecret"
          |            }
          |        },
          |        "exec": {
          |            "command": {
          |                "shell": "sleep 1"
          |            }
          |        }
          |    }],
          |    "secrets": {
          |        "pullConfigSecret": {
          |            "source": "/config"
          |        }
          |    }
          |}
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.create(podJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("pullConfig is supported only with Docker images")
      }
    }

    "Creating a new pod with w/ Docker image and non-existing secret should fail" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--enable_features", "secrets"))

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podJson =
        """
          |{
          |    "id": "/pod",
          |    "containers": [{
          |        "name": "container0",
          |        "resources": {
          |            "cpus": 0.1,
          |            "mem": 32
          |        },
          |        "image": {
          |            "kind": "Docker",
          |            "id": "private/image",
          |            "pullConfig": {
          |                "secret": "pullConfigSecret"
          |            }
          |        },
          |        "exec": {
          |            "command": {
          |                "shell": "sleep 1"
          |            }
          |        }
          |    }],
          |    "secrets": {
          |        "pullConfigSecretA": {
          |            "source": "/config"
          |        }
          |    }
          |}
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.create(podJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("pullConfig.secret must refer to an existing secret")
      }
    }

    "Create a new pod with w/ Docker image and config.json, but with secrets disabled" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val podJson =
        """
          |{
          |    "id": "/pod",
          |    "containers": [{
          |        "name": "container0",
          |        "resources": {
          |            "cpus": 0.1,
          |            "mem": 32
          |        },
          |        "image": {
          |            "kind": "DOCKER",
          |            "id": "private/image",
          |            "pullConfig": {
          |                "secret": "pullConfigSecret"
          |            }
          |        },
          |        "exec": {
          |            "command": {
          |                "shell": "sleep 1"
          |            }
          |        }
          |    }]
          |}
        """.stripMargin

      val response = asyncRequest { r =>
        f.podsResource.create(podJson.getBytes(), force = false, f.auth.request, r)
      }

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("must be empty")
        response.getEntity.toString should include("Feature secrets is not enabled. Enable with --enable_features secrets)")
        response.getEntity.toString should include("pullConfig.secret must refer to an existing secret")
      }
    }

    "support versions" when {

      "there are no versions" when {
        "list no versions" in {
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(None)
          implicit val podManager = PodManagerImpl(groupManager)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
          }
        }
        "return 404 when asking for a version" in {
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(None)
          groupManager.podVersions(any).returns(Source.empty)
          groupManager.podVersion(any, any).returns(Future.successful(None))
          implicit val podManager = PodManagerImpl(groupManager)
          val f = Fixture()

          val response = f.podsResource.version("/id", "2008-01-01T12:00:00.000Z", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
            response.getEntity.toString should be ("{\"message\":\"Pod '/id' does not exist\"}")
          }
        }
      }
      "there are versions" when {
        import mesosphere.marathon.state.PathId._
        val pod1 = PodDefinition("/id".toRootPath, containers = Seq(MesosContainer(name = "foo", resources = Resources())))
        val pod2 = pod1.copy(versionInfo = VersionInfo.OnlyVersion(pod1.version + 1.minute))
        "list the available versions" in {
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(Some(pod2))
          groupManager.podVersions(pod1.id).returns(Source(Seq(pod1.version.toOffsetDateTime, pod2.version.toOffsetDateTime)))

          implicit val podManager = PodManagerImpl(groupManager)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val timestamps = Json.fromJson[Seq[Timestamp]](Json.parse(response.getEntity.asInstanceOf[String])).get
            timestamps should contain theSameElementsAs Seq(pod1.version, pod2.version)
          }
        }
        "get a specific version" in {
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(Some(pod2))
          groupManager.podVersions(pod1.id).returns(Source(Seq(pod1.version.toOffsetDateTime, pod2.version.toOffsetDateTime)))
          groupManager.podVersion(pod1.id, pod1.version.toOffsetDateTime).returns(Future.successful(Some(pod1)))
          groupManager.podVersion(pod1.id, pod2.version.toOffsetDateTime).returns(Future.successful(Some(pod2)))
          implicit val podManager = PodManagerImpl(groupManager)
          val f = Fixture()

          val response = f.podsResource.version("/id", pod1.version.toString, f.auth.request)
          withClue(s"reponse body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val pod = Raml.fromRaml(Json.fromJson[Pod](Json.parse(response.getEntity.asInstanceOf[String])).get)
            pod should equal(pod1)
          }
        }
      }
      "killing" when {
        "attempting to kill a single instance" in {
          implicit val killer = mock[TaskKiller]
          val f = Fixture()
          val runSpec = AppDefinition(id = "/id1".toRootPath, versionInfo = VersionInfo.OnlyVersion(f.clock.now()))
          val instanceId = Instance.Id.fromIdString("id1.instance-a905036a-f6ed-11e8-9688-2a978491fd64")
          val instance = Instance(
            instanceId, Some(Instance.AgentInfo("", None, None, None, Nil)),
            InstanceState(Condition.Running, f.clock.now(), Some(f.clock.now()), None, Goal.Running),
            Map.empty,
            runSpec = runSpec,
            None
          )
          killer.kill(any, any, any)(any) returns Future.successful(Seq(instance))
          val response = asyncRequest { r =>
            f.podsResource.killInstance(instanceId.runSpecId.safePath, instance.instanceId.idString, false, f.auth.request, r)
          }
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val actual = response.getEntity.asInstanceOf[String]
            val expected =
              """
                | {
                | "instanceId": "id1.instance-a905036a-f6ed-11e8-9688-2a978491fd64",
                | "agentInfo": {
                |   "host":"",
                |   "attributes":[]
                |   },
                | "runSpecVersion":"2015-04-09T12:30:00.000Z",
                | "state": {
                |   "condition":"Running",
                |   "since":"2015-04-09T12:30:00.000Z",
                |   "activeSince":"2015-04-09T12:30:00.000Z",
                |   "goal":"Running"
                |   },
                | "unreachableStrategy": { "inactiveAfterSeconds":0, "expungeAfterSeconds":0 }
                | }
              """.stripMargin
            JsonTestHelper.assertThatJsonString(actual).correspondsToJsonString(expected)
          }
        }
        "attempting to kill multiple instances" in {
          implicit val killer = mock[TaskKiller]
          val runSpec = AppDefinition(id = "/id1".toRootPath, unreachableStrategy = UnreachableStrategy.default())
          val instances = Seq(
            Instance(Instance.Id.forRunSpec(runSpec.id), Some(Instance.AgentInfo("", None, None, None, Nil)),
              InstanceState(Condition.Running, Timestamp.now(), Some(Timestamp.now()), None, Goal.Running), Map.empty,
              runSpec,
              None
            ),
            Instance(Instance.Id.forRunSpec(runSpec.id), Some(Instance.AgentInfo("", None, None, None, Nil)),
              InstanceState(Condition.Running, Timestamp.now(), Some(Timestamp.now()), None, Goal.Running), Map.empty,
              runSpec,
              None))

          val f = Fixture()

          killer.kill(any, any, any)(any) returns Future.successful(instances)
          val response = asyncRequest { r =>
            f.podsResource.killInstances(
              "/id", false, Json.stringify(Json.toJson(instances.map(_.instanceId.idString))).getBytes, f.auth.request, r)
          }
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val killed: Seq[raml.Instance] = Json.fromJson[Seq[raml.Instance]](Json.parse(response.getEntity.asInstanceOf[String])).get
            killed.map(_.instanceId) should contain theSameElementsAs instances.map(_.instanceId.idString)
          }
        }
      }
    }

    "authentication and authorization is handled correctly" when {
      "delete fails if not authorized" when {
        "delete a pod without auth access" in {
          implicit val podSystem = mock[PodManager]
          val f = Fixture()
          podSystem.find(any).returns(Some(PodDefinition()))
          podSystem.delete(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
          f.auth.authorized = false
          val response = asyncRequest { r =>
            f.podsResource.remove("/mypod", force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

      }
    }

    "access without authentication is denied" when {

      class UnAuthorizedFixture(authorized: Boolean, authenticated: Boolean) {
        implicit val podSystem = mock[PodManager]
        val fixture = Fixture()
        podSystem.findAll(any).returns(Seq.empty)
        podSystem.find(any).returns(Some(PodDefinition()))
        podSystem.delete(any, any).returns(Future.successful(DeploymentPlan.empty))
        podSystem.ids().returns(Set.empty)
        podSystem.version(any, any).returns(Future.successful(Some(PodDefinition())))
        fixture.auth.authorized = authorized
        fixture.auth.authenticated = authenticated
      }

      "An unauthorized but authenticated request" when {
        val f = new UnAuthorizedFixture(authorized = false, authenticated = true).fixture

        "create a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.create(podSpecJson.getBytes, force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "update a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.update("mypod", podSpecJson.getBytes, force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "find a pod" in {
          val response = syncRequest { f.podsResource.find("mypod", f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "remove a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.remove("mypod", force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "versions of a pod" in {
          val response = syncRequest { f.podsResource.versions("mypod", f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "version of a pod" in {
          val response = syncRequest { f.podsResource.version("mypod", Timestamp.now().toString, f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }
      }

      "An unauthenticated (and therefore unauthorized) request" when {
        val f = new UnAuthorizedFixture(authorized = false, authenticated = false).fixture

        "create a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.create(podSpecJson.getBytes, force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "update a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.update("mypod", podSpecJson.getBytes, force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "find a pod" in {
          val response = syncRequest { f.podsResource.find("mypod", f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "remove a pod" in {
          val response = asyncRequest { r =>
            f.podsResource.remove("mypod", force = false, f.auth.request, r)
          }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "status of a pod" in {
          val response = syncRequest { f.podsResource.status("mypod", f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "versions of a pod" in {
          val response = syncRequest { f.podsResource.versions("mypod", f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "version of a pod" in {
          val response = syncRequest { f.podsResource.version("mypod", Timestamp.now().toString, f.auth.request) }
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }
      }
    }
  }

  case class Fixture(
      podsResource: PodsResource,
      auth: TestAuthFixture,
      podSystem: PodManager,
      clock: SettableClock
  )

  object Fixture {
    def apply(
      configArgs: Seq[String] = Seq.empty[String],
      auth: TestAuthFixture = new TestAuthFixture()
    )(implicit
      podSystem: PodManager = mock[PodManager],
      podStatusService: PodStatusService = mock[PodStatusService],
      killService: TaskKiller = mock[TaskKiller],
      eventBus: EventStream = mock[EventStream],
      mat: Materializer = mock[Materializer],
      scheduler: MarathonScheduler = mock[MarathonScheduler]): Fixture = {
      val config = AllConf.withTestConfig(configArgs: _*)
      implicit val authz: Authorizer = auth.auth
      implicit val authn: Authenticator = auth.auth
      implicit val clock = new SettableClock()
      implicit val pluginManager: PluginManager = PluginManager.None
      scheduler.mesosMasterVersion() returns Some(SemanticVersion(0, 0, 0))
      new Fixture(
        new PodsResource(config),
        auth,
        podSystem,
        clock
      )
    }
  }
}
