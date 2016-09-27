package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import akka.stream.Materializer
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.appinfo.PodStatusService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.impl.PodManagerImpl
import mesosphere.marathon.core.pod.{PodDefinition, PodManager}
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer}
import mesosphere.marathon.raml.{Pod, Raml}
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.storage.repository.PodRepository
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.immutable.Seq

class PodsResourceTest extends AkkaUnitTest with Mockito {

  // TODO(jdef) test findAll
  // TODO(jdef) test status
  // TODO(jdef) incorporate checks for firing pod events on C, U, D operations

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

      val postJson = """
                       | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                       |   { "name": "webapp",
                       |     "resources": { "cpus": 0.03, "mem": 64 },
                       |     "image": { "kind": "DOCKER", "id": "busybox" },
                       |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                     """.stripMargin
      val response = f.podsResource.create(postJson.getBytes(), false, f.auth.request)

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
          implicit val podManager = PodManagerImpl(mock[GroupManager], podRepository)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_OK)
            val body = response.getEntity.asInstanceOf[String]
            body should equal("[]")
          }
        }
        "return 404 when asking for a version" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          implicit val podManager = PodManagerImpl(mock[GroupManager], podRepository)
          val f = Fixture()

          val response = f.podsResource.version("/id", "2008", f.auth.request)
          withClue(s"response body: ${response.getEntity}") {
            response.getStatus should be(HttpServletResponse.SC_NOT_FOUND)
          }
        }
      }
      "there are versions" when {
        import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
        import mesosphere.marathon.state.PathId._
        val pod1 = PodDefinition("/id".toRootPath)
        val pod2 = pod1.copy(version = pod1.version + 1.minute)
        "list the available versions" in {
          val podRepository = PodRepository.inMemRepository(new InMemoryPersistenceStore())
          podRepository.store(pod1).futureValue
          podRepository.store(pod2).futureValue
          implicit val podManager = PodManagerImpl(mock[GroupManager], podRepository)
          val f = Fixture()

          val response = f.podsResource.versions("/id", f.auth.request)
          withClue(s"reponse body: ${response.getEntity}") {
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
