package mesosphere.marathon
package experimental.repository

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.core.storage.zookeeper.{AsyncCuratorBuilderFactory, ZooKeeperPersistenceStore}
import mesosphere.marathon.experimental.storage.PathTrieTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.util.ZookeeperServerTest
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

class SyncTemplateRepositoryTest
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
  lazy val repo: SyncTemplateRepository = new SyncTemplateRepository(store, base)

  val rand = new Random()

  def appDef(pathId: PathId): AppDefinition = AppDefinition(id = pathId)
  def randomApp(): AppDefinition = appDef(randomPath())
  def randomPath(prefix: String = "/test"): PathId = PathId(s"$prefix${rand.nextInt}")

  def prettyPrint(): Unit = PathTrieTest.prettyPrint(repo.trie)

  /**
    * Pre-populate Zookeeper store, bypassing the repository.
    */
  def populate(apps: Seq[AppDefinition]): Future[Done] = {
    Source(apps)
      .map(app => repo.toNode(app))
      .via(store.createFlow)
      .runWith(Sink.ignore)
  }

  "SyncTemplateRepository" when {
    "initialize" should {
      "successfully initialize from an empty zookeeper state" in {
        Given("this is the first test in the suite")
        And("an empty store")

        And("repository is initialized")
        repo.initialize().futureValue

        Then("repository in-memory state is empty")
        repo.trie.getChildren(base, true).asScala shouldBe null
      }

      "successfully load existing zookeeper templates" in {
        Given("a store with existing templates")
        val paths = List(
          "/eng/dev/foo",
          "/eng/dev/bar",
          "/eng/dev/qa/service1",
          "/eng/dev/qa/soak/database/mysql",
          "/sales/demo/twitter",
          "/eng/ui/tests",
          "/eng/ui/jenkins/jobs/master"
        )

        val apps: List[AppDefinition] = paths.map(p => appDef(PathId(p)))
        populate(apps).futureValue

        And("repository is initialized")
        repo.initialize().futureValue
        prettyPrint()

        Then("repository in-memory state is populated")
        val leafs = apps.map(repo.storePath(_))
        repo.trie.getLeafs("/").asScala should contain theSameElementsAs (leafs)

        apps.foreach { app =>
          val read = repo.trie.getNodeData(repo.storePath(app))
          repo.toTemplate(read, AppDefinition(id = app.id)).get shouldBe app
        }
      }
    }

    "create" should {
      "create a new template successfully" in {
        Given("a new template")
        val app = randomApp()

        Then("it can be stored be successful")
        repo.create(app).futureValue shouldBe Done

        And("underlying store should have the template stored")
        store.children(repo.storePath(app.id), true).futureValue.size shouldBe 1

        And("template should be also stored in the trie")
        repo.trie.getNodeData(repo.storePath(app)) shouldBe app.toProtoByteArray
      }

      "create two versions of the same template successfully" in {
        Given("a new template is created")
        val pathId = randomPath("/foo/bar/test")
        val created = appDef(pathId)

        Then("operation should be successful")
        repo.create(created).futureValue shouldBe Done

        And("A new template version is stored")
        val updated = created.copy(instances = 2)
        repo.create(updated).futureValue shouldBe Done

        Then("two app versions should be stored")
        val versions = store.children(repo.storePath(created.id), true).futureValue
        versions.size shouldBe 2

        And("saved versions should be hash-codes of the stored apps")
        versions should contain theSameElementsAs Seq(created, updated).map(repo.storePath(_))

        And("saved versions should be stored in the trie")
        repo.trie.getNodeData(repo.storePath(created)) shouldBe created.toProtoByteArray
        repo.trie.getNodeData(repo.storePath(updated)) shouldBe updated.toProtoByteArray
      }

      "fail to create a new template with an existing version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue shouldBe Done

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

        repo.create(created).futureValue shouldBe Done

        Then("it can be read and parsed successfully")
        val dummy = AppDefinition(id = created.id)
        val read = repo.readSync(dummy, repo.version(created)).get
        read shouldBe created
      }

      "fail to read an non-existing template" in {
        When("trying to read an non-existing template")
        val dummy = randomApp()

        Then("operation should fail")
        intercept[NoNodeException] {
          repo.readSync(dummy, repo.version(dummy)).get
        }
      }
    }

    "delete" should {
      "successfully delete an existing template" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue shouldBe Done

        And("it can be deleted")
        repo.delete(app).futureValue shouldBe Done

        Then("the version should not be in the store")
        store.exists(repo.storePath(app)).futureValue shouldBe false

        And("not in the trie")
        repo.trie.existsNode(repo.storePath(app)) shouldBe false

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
        Given("a new template with a few versions is created")
        val first = randomApp()
        val second = first.copy(instances = 2)
        repo.create(first).futureValue shouldBe Done
        repo.create(second).futureValue shouldBe Done

        Then("versions should return existing versions")
        repo.contentsSync(first.id).get should contain theSameElementsAs Seq(first, second).map(repo.storePath)
      }

      "return an empty sequence for a template without versions" in {
        When("a new template with a few versions is created")
        val pathId = randomPath()
        val app = appDef(pathId)
        repo.create(app).futureValue shouldBe Done

        And("app version is deleted")
        repo.delete(pathId, repo.version(app)).futureValue shouldBe Done

        Then("contents of that path is an empty sequence")
        repo.contentsSync(pathId).get.isEmpty shouldBe true
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
        val app = randomApp()
        repo.create(app).futureValue shouldBe Done

        Then("exist should return true for the template pathId")
        repo.existsSync(app.id) shouldBe true
      }

      "return true for an existing template version" in {
        Given("a new template is successfully created")
        val app = randomApp()
        repo.create(app).futureValue shouldBe Done

        Then("exist should return true for the template version")
        repo.existsSync(app) shouldBe true
      }

      "return false for a non-existing template" in {
        Then("exist should return false for a non-existing template")
        repo.exists(randomPath()).futureValue shouldBe false
      }
    }
  }
}
