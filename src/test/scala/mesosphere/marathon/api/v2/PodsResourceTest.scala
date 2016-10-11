package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon._
import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
import mesosphere.marathon.api.{ TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.raml.{ FixedPodScalingPolicy, Pod, Raml }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.storage.repository.PodRepository
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

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
      val f = Fixture()

      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

      val response = f.podsResource.create(podSpecJson.getBytes(), false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_CREATED)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should not be (None)
        parsedResponse.map(_.as[Pod]) should not be (None) // validate that we DID get back a pod definition

        response.getMetadata.containsKey(PodsResource.DeploymentHeader) should be(true)
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
      val response = f.podsResource.update("/mypod", postJson.getBytes(), false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should not be (None)
        parsedResponse.map(_.as[Pod]) should not be (None) // validate that we DID get back a pod definition

        response.getMetadata.containsKey(PodsResource.DeploymentHeader) should be(true)
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
      val response = f.podsResource.update("/mypod", postJson.getBytes(), false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_OK)

        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
        parsedResponse should not be None
        val podOption = parsedResponse.map(_.as[Pod])
        podOption should not be None // validate that we DID get back a pod definition

        response.getMetadata.containsKey(PodsResource.DeploymentHeader) should be(true)
        podOption.get.scaling should not be None
        podOption.get.scaling.get shouldBe a[FixedPodScalingPolicy]
        podOption.get.scaling.get.asInstanceOf[FixedPodScalingPolicy].instances should be (2)
      }
    }

    "delete a pod" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.find(any).returns(Future.successful(Some(PodDefinition())))
      podSystem.delete(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
      val response = f.podsResource.remove("/mypod", false, f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_ACCEPTED)

        val body = Option(response.getEntity.asInstanceOf[String])
        body should be(None)

        response.getMetadata.containsKey(PodsResource.DeploymentHeader) should be(true)
      }
    }

    "lookup a specific pod, and that pod does not exist" in {
      implicit val podSystem = mock[PodManager]
      val f = Fixture()

      podSystem.find(any).returns(Future.successful(Option.empty[PodDefinition]))
      val response = f.podsResource.find("/mypod", f.auth.request)

      withClue(s"response body: ${response.getEntity}") {
        response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
        val body = Option(response.getEntity.asInstanceOf[String])
        body should not be (None)
        body.foreach(_ should include("mypod does not exist"))
      }
    }

    "support versions" when {
      implicit val metrics = new Metrics(new MetricRegistry)
      implicit val ctx = ExecutionContext.global

      "there are no versions" when {
        "list no versions" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(Future.successful(None))
          implicit val podManager = PodManagerImpl(groupManager, podRepository)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
          }
        }
        "return 404 when asking for a version" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(Future.successful(None))
          implicit val podManager = PodManagerImpl(groupManager, podRepository)
          val f = Fixture()

          val response = f.podsResource.version("/id", "2008", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
          }
        }
      }
      "there are versions" when {
        import mesosphere.marathon.state.PathId._
        val pod1 = PodDefinition("/id".toRootPath)
        val pod2 = pod1.copy(version = pod1.version + 1.minute)
        "list the available versions" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          podRepository.store(pod1).futureValue
          podRepository.store(pod2).futureValue
          val groupManager = mock[GroupManager]
          groupManager.pod(any).returns(Future.successful(Some(pod2)))
          implicit val podManager = PodManagerImpl(groupManager, podRepository)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val timestamps = Json.fromJson[Seq[Timestamp]](Json.parse(response.getEntity.asInstanceOf[String])).get
            timestamps should contain theSameElementsAs Seq(pod1.version, pod2.version)
          }
        }
        "get a specific version" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          podRepository.store(pod1).futureValue
          podRepository.store(pod2).futureValue
          implicit val podManager = PodManagerImpl(mock[GroupManager], podRepository)
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
          val instance = Instance(Instance.Id.forRunSpec("/id1".toRootPath), Instance.AgentInfo("", None, Nil),
            InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), None), Map.empty)
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
              InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), None), Map.empty),
            Instance(Instance.Id.forRunSpec("/id1".toRootPath), Instance.AgentInfo("", None, Nil),
              InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), None), Map.empty))

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
          podSystem.find(any).returns(Future.successful(Some(PodDefinition())))
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
        podSystem.findAll(any).returns(Source.empty)
        podSystem.find(any).returns(Future.successful(Some(PodDefinition())))
        podSystem.delete(any, any).returns(Future.successful(DeploymentPlan.empty))
        podSystem.ids().returns(Source.empty)
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
      mat: Materializer = mock[Materializer]): Fixture = {
      val config = AllConf.withTestConfig(configArgs: _*)
      implicit val authz: Authorizer = auth.auth
      implicit val authn: Authenticator = auth.auth
      new Fixture(
        new PodsResource(config),
        auth,
        podSystem
      )
    }
  }
}
