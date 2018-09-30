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
  lazy val repository: AsyncTemplateRepository = new AsyncTemplateRepository(store, base)

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
  def rawStore(pathId: PathId, payload: ByteString = ByteString.empty): Future[String] = store.create(Node(repository.toPath(pathId), payload))

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
        repository.create(app).futureValue shouldBe Done

        And("underlying store should have the template stored")
        store.children(repository.toPath(app.id), true).futureValue.size shouldBe 1
      }

      "create two versions of the same template successfully" in {
        Given("a new template is created")
        val pathId = randomPath("/foo/bar/test")
        val created = appDef(pathId)

        Then("operation should be successful")
        repository.create(created).futureValue shouldBe Done

        And("A new template version is stored")
        val updated = created.copy(instances = 2)
        repository.create(updated).futureValue shouldBe Done

        Then("two app versions should be stored")
        val versions = store.children(repository.toPath(created.id), true).futureValue
        versions.size shouldBe 2

        And("saved versions should be hash-codes of the stored apps")
        versions should contain theSameElementsAs Seq(created, updated).map(repository.toPath(_))
      }

      "fail to create a new template with an existing version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repository.create(app).futureValue shouldBe Done

        And("the same template is stored again an exception is thrown")
        intercept[NodeExistsException] {
          Await.result(repository.create(app), Duration.Inf)
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

        repository.create(created).futureValue shouldBe Done

        Then("it can be read and parsed successfully")
        val dummy = AppDefinition(id = created.id)
        val read = repository.read(dummy, repository.version(created)).futureValue.get
        read shouldBe created
      }

      "fail to read an non-existing template" in {
        When("trying to read an non-existing template")
        val dummy = randomApp()

        Then("operation should fail")
        intercept[NoNodeException] {
          Await.result(repository.read(dummy, repository.version(dummy)), Duration.Inf).get
        }
      }
    }

    "delete" should {
      "successfully delete an existing template" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repository.create(app).futureValue shouldBe Done

        And("it can be deleted")
        repository.delete(app).futureValue shouldBe Done

        Then("the version should not be in the store")
        store.exists(repository.toPath(app)).futureValue shouldBe false

        And("but the template itself should")
        store.exists(repository.toPath(app.id)).futureValue shouldBe true
      }

      "successfully delete a non-existing template" in {
        Then("deleting a non-existing template is successful")
        repository.delete(randomPath()).futureValue shouldBe Done
      }
    }

    "contents" should {
      "return existing versions for a template" in {
        def toRelativePath(app: AppDefinition) = Paths.get(app.id.toString, Math.abs(app.hashCode).toString).toString

        Given("a new template with a few versions is created")
        val first = randomApp()
        val second = first.copy(instances = 2)
        repository.create(first).futureValue shouldBe Done
        repository.create(second).futureValue shouldBe Done

        Then("versions should return existing versions")
        repository.contents(first.id).futureValue should contain theSameElementsAs Seq(first, second).map(toRelativePath)
      }

      "return an empty sequence for a template without versions" in {
        When("a new template with a few versions is created")
        val pathId = randomPath()
        rawStore(pathId)

        Then("contents of that path is an empty sequence")
        repository.contents(pathId).futureValue.isEmpty shouldBe true
      }

      "fail for a non-existing pathId" in {
        Then("contents should fail for a non-existing pathId")
        intercept[NoNodeException] {
          Await.result(repository.contents(randomPath()), Duration.Inf)
        }
      }
    }

    "exist" should {
      "return true for an existing template" in {
        Given("a new template without versions is created")
        val pathId = randomPath()
        rawStore(pathId)

        Then("exist should return true for the template version")
        repository.exists(pathId).futureValue shouldBe true
      }

      "return true for and existing template version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repository.create(app).futureValue shouldBe Done

        Then("exist should return true for the template version")
        repository.exists(app).futureValue shouldBe true
      }

      "return false for a non-existing template" in {
        Then("exist should return false for a non-existing template")
        repository.exists(randomPath()).futureValue shouldBe false
      }
    }
  }
}
