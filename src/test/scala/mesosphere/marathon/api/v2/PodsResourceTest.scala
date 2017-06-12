package mesosphere.marathon
package api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
import mesosphere.marathon.api.{ RestResource, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition, PodManager }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.raml.{ EnvVarSecret, ExecutorResources, FixedPodScalingPolicy, NetworkMode, Pod, PodSecretVolume, Raml, Resources }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Timestamp, UnreachableStrategy }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.util.SemanticVersion
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class PodsResourceTest extends AkkaUnitTest with Mockito {

  // TODO(jdef) test findAll
  // TODO(jdef) test status
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

      val response = f.podsResource.create(podSpecJson.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podSpecJsonWithBridgeNetwork.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs) fails" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = f.podsResource.create(podSpecJsonWithEnvRefSecret.getBytes(), force = false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is NOT enabled and create pod (that uses env secret refs on container level) fails" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("Feature secrets is not enabled")
      }
    }

    "The secrets feature is enabled and create pod (that uses env secret refs on container level) succeeds" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request)

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

      val ex = intercept[NormalizationException] {
        f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request)
      }
      ex.msg shouldBe NetworkNormalizationMessages.ContainerNetworkNameUnresolved
    }

    "create a pod with custom executor resource declaration" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = f.podsResource.create(podSpecJsonWithExecutorResources.getBytes(), force = false, f.auth.request)

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
      val response = f.podsResource.update("/mypod", postJson.getBytes(), force = false, f.auth.request)

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
      val response = f.podsResource.update("/mypod", postJson.getBytes(), force = false, f.auth.request)

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

    "delete a pod" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.find(any).returns(Some(PodDefinition()))
      podSystem.delete(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
      val response = f.podsResource.remove("/mypod", force = false, f.auth.request)

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

      val response = f.podsResource.create(podJson.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podJson.getBytes(), force = false, f.auth.request)

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

      val response = f.podsResource.create(podJson.getBytes(), force = false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(422)
        response.getEntity.toString should include("pullConfig.secret must refer to an existing secret")
      }
    }

    "support versions" when {
      implicit val ctx = ExecutionContexts.global

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

          val response = f.podsResource.version("/id", "2008", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
            response.getEntity.toString should be ("{\"message\":\"Pod '/id' does not exist\"}")
          }
        }
      }
      "there are versions" when {
        import mesosphere.marathon.state.PathId._
        val pod1 = PodDefinition("/id".toRootPath, containers = Seq(MesosContainer(name = "foo", resources = Resources())))
        val pod2 = pod1.copy(version = pod1.version + 1.minute)
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
          val instance = Instance(
            Instance.Id.forRunSpec("/id1".toRootPath), Instance.AgentInfo("", None, Nil),
            InstanceState(Condition.Running, Timestamp.now(), Some(Timestamp.now()), None),
            Map.empty,
            runSpecVersion = Timestamp.now(),
            unreachableStrategy = UnreachableStrategy.default()
          )
          killer.kill(any, any, any)(any) returns Future.successful(Seq(instance))
          val response = f.podsResource.killInstance("/id", instance.instanceId.toString, f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val killed = Json.fromJson[Instance](Json.parse(response.getEntity.asInstanceOf[String]))
            killed.get should equal(instance)
          }
        }
        "attempting to kill multiple instances" in {
          implicit val killer = mock[TaskKiller]
          val instances = Seq(
            Instance(Instance.Id.forRunSpec("/id1".toRootPath), Instance.AgentInfo("", None, Nil),
              InstanceState(Condition.Running, Timestamp.now(), Some(Timestamp.now()), None), Map.empty,
              runSpecVersion = Timestamp.now(),
              unreachableStrategy = UnreachableStrategy.default()
            ),
            Instance(Instance.Id.forRunSpec("/id1".toRootPath), Instance.AgentInfo("", None, Nil),
              InstanceState(Condition.Running, Timestamp.now(), Some(Timestamp.now()), None), Map.empty,
              runSpecVersion = Timestamp.now(),
              unreachableStrategy = UnreachableStrategy.default()))

          val f = Fixture()

          killer.kill(any, any, any)(any) returns Future.successful(instances)
          val response = f.podsResource.killInstances(
            "/id",
            Json.stringify(Json.toJson(instances.map(_.instanceId.toString))).getBytes, f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val killed = Json.fromJson[Seq[Instance]](Json.parse(response.getEntity.asInstanceOf[String]))
            killed.get should contain theSameElementsAs instances
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
          val response = f.podsResource.remove("/mypod", force = false, f.auth.request)
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
          val response = f.podsResource.create(podSpecJson.getBytes, force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "update a pod" in {
          val response = f.podsResource.update("mypod", podSpecJson.getBytes, force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "find a pod" in {
          val response = f.podsResource.find("mypod", f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "remove a pod" in {
          val response = f.podsResource.remove("mypod", force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "status of a pod" in {
          val response = f.podsResource.remove("mypod", force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "versions of a pod" in {
          val response = f.podsResource.versions("mypod", f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }

        "version of a pod" in {
          val response = f.podsResource.version("mypod", Timestamp.now().toString, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_UNAUTHORIZED)
        }
      }

      "An unauthenticated (and therefore unauthorized) request" when {
        val f = new UnAuthorizedFixture(authorized = false, authenticated = false).fixture

        "create a pod" in {
          val response = f.podsResource.create(podSpecJson.getBytes, force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "update a pod" in {
          val response = f.podsResource.update("mypod", podSpecJson.getBytes, force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "find a pod" in {
          val response = f.podsResource.find("mypod", f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "remove a pod" in {
          val response = f.podsResource.remove("mypod", force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "status of a pod" in {
          val response = f.podsResource.remove("mypod", force = false, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "versions of a pod" in {
          val response = f.podsResource.versions("mypod", f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }

        "version of a pod" in {
          val response = f.podsResource.version("mypod", Timestamp.now().toString, f.auth.request)
          response.getStatus should be(HttpServletResponse.SC_FORBIDDEN)
        }
      }
    }
  }

  case class Fixture(
    podsResource: PodsResource,
    auth: TestAuthFixture,
    podSystem: PodManager
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
      implicit val clock = ConstantClock()
      scheduler.mesosMasterVersion() returns Some(SemanticVersion(0, 0, 0))
      new Fixture(
        new PodsResource(config),
        auth,
        podSystem
      )
    }
  }
}
