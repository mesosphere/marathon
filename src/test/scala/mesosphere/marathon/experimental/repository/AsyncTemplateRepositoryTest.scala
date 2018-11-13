package mesosphere.marathon
package experimental.repository

import java.nio.file.Paths

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.{AsyncCuratorBuilderFactory, ZooKeeperPersistenceStore}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.util.ZookeeperServerTest
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import TemplateRepositoryLike._

class AsyncTemplateRepositoryTest
  extends UnitTest
  with ZookeeperServerTest
  with StrictLogging {

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  lazy val client: CuratorFramework = zkClient(namespace = Some("test")).client
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(client)
  lazy val metrics: Metrics = DummyMetrics
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(metrics, factory, parallelism = 1)

  val base = "/templates"
  lazy val repo: AsyncTemplateRepository = new AsyncTemplateRepository(store, base)

  val rand = new Random()

  def appDef(pathId: PathId): AppDefinition = AppDefinition(id = pathId)
  def randomApp(): AppDefinition = appDef(randomPath())
  def randomPath(prefix: String = "/test"): PathId = PathId(s"$prefix${rand.nextInt}")

  /**
    * Raw store methods to create templates, bypassing the repository.
    *
    * @param pathId templates' pathId
    * @param version optionally, template's version
    * @return
    */
  def rawStore(pathId: PathId, payload: ByteString = ByteString.empty): Future[String] = store.create(Node(repo.storePath(pathId), payload))

  def prettyPrint(path: String = "/", indent: String = " "): Unit = {
    val children = store.children(path, true).futureValue.toList.sorted

    children.foreach { child =>
      logger.info(s"$indent|_${Paths.get(child).getFileName}")
      prettyPrint(child, indent.padTo(indent.length + 3, ' '))
    }
  }

  "AsyncTemplateRepository" when {
    "create" should {
      "create a new template successfully" in {
        Given("a new template")
        val app = randomApp()

        Then("it can be stored be successful")
        repo.create(app).futureValue shouldBe repo.version(app)

        And("underlying store should have the template stored")
        store.children(repo.storePath(app.id), true).futureValue.size shouldBe 1
      }

      "create two versions of the same template successfully" in {
        Given("a new template is created")
        val pathId = randomPath("/foo/bar/test")
        val created = appDef(pathId)

        Then("operation should be successful")
        repo.create(created).futureValue shouldBe repo.version(created)

        And("A new template version is stored")
        val updated = created.copy(instances = 2)
        repo.create(updated).futureValue shouldBe repo.version(updated)

        Then("two app versions should be stored")
        val versions = store.children(repo.storePath(created.id), true).futureValue
        versions.size shouldBe 2

        And("saved versions should be hash-codes of the stored apps")
        versions should contain theSameElementsAs Seq(created, updated).map(repo.storePath(_))
      }

      "fail to create a new template with an existing version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue shouldBe repo.version(app)

        And("the same template is stored again an exception is thrown")
        intercept[NodeExistsException] {
          Await.result(repo.create(app), Duration.Inf)
        }
      }
    }

    "read" should {
      "read an existing template" in {
        Given("a new template is successfully created")
        val created = appDef(randomPath()).copy( // a non-default app definition
          cmd = Some("sleep 12345"),
          instances = 2,
          labels = Map[String, String]("FOO" -> "bar"))

        repo.create(created).futureValue

        Then("it can be read and parsed successfully")
        val dummy = AppDefinition(id = created.id)
        val read = repo.read(dummy, repo.version(created)).futureValue
        read shouldBe created
      }

      "fail to read an non-existing template" in {
        When("trying to read an non-existing template")
        val dummy = randomApp()

        Then("operation should fail")
        intercept[NoNodeException] {
          Await.result(repo.read(dummy, repo.version(dummy)), Duration.Inf)
        }
      }
    }

    "delete" should {
      "successfully delete an existing template" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue

        And("it can be deleted")
        repo.delete(app).futureValue shouldBe Done

        Then("the version should not be in the store")
        store.exists(repo.storePath(app)).futureValue shouldBe false

        And("but the template itself should")
        store.exists(repo.storePath(app.id)).futureValue shouldBe true
      }

      "successfully delete a non-existing template" in {
        Then("deleting a non-existing template is successful")
        repo.delete(randomPath()).futureValue shouldBe Done
      }
    }

    "contents" should {
      "return existing versions for a template" in {
        def toPath(t: Template[_]): String = Paths.get(t.id.toString, repo.version(t)).toString

        Given("a new template with a few versions is created")
        val first = randomApp()
        val second = first.copy(instances = 2)
        repo.create(first).futureValue
        repo.create(second).futureValue

        Then("versions should return existing versions")
        repo.contents(first.id).futureValue should contain theSameElementsAs Seq(first, second).map(toPath(_))
      }

      "return an empty sequence for a template without versions" in {
        When("a new template with a few versions is created")
        val pathId = randomPath()
        rawStore(pathId)

        Then("contents of that path is an empty sequence")
        repo.contents(pathId).futureValue.isEmpty shouldBe true
      }

      "fail for a non-existing pathId" in {
        Then("contents should fail for a non-existing pathId")
        intercept[NoNodeException] {
          Await.result(repo.contents(randomPath()), Duration.Inf)
        }
      }
    }

    "exist" should {
      "return true for an existing template" in {
        Given("a new template without versions is created")
        val pathId = randomPath()
        rawStore(pathId)

        Then("exist should return true for the template version")
        repo.exists(pathId).futureValue shouldBe true
      }

      "return true for and existing template version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue

        Then("exist should return true for the template version")
        repo.exists(app).futureValue shouldBe true
      }

      "return false for a non-existing template" in {
        Then("exist should return false for a non-existing template")
        repo.exists(randomPath()).futureValue shouldBe false
      }
    }
  }
}
