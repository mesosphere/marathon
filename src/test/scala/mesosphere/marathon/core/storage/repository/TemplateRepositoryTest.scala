package mesosphere.marathon
package core.storage.repository

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.{Template, Versioned}
import mesosphere.marathon.core.storage.repository.impl.TemplateRepository
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.{AsyncCuratorBuilderFactory, ZooKeeperPersistenceStore}
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.util.ZookeeperServerTest
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

class TemplateRepositoryTest
  extends UnitTest
  with ZookeeperServerTest
  with StrictLogging {

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  lazy val client: CuratorFramework = zkClient(namespace = Some("test")).client
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(client)
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(factory, parallelism = 1)

  lazy val repository: TemplateRepository = new TemplateRepository(store)

  val rand = new Random()

  import TemplateRepository._

  def template(pathId: PathId): Template = AppDefinition(id = pathId)
  def randomTemplate(): Template = template(PathId(s"/test-${rand.nextInt}"))

  def node(pathId: PathId, version: Option[Int]) = version match {
    case Some(ver) => Node(repository.path(VersionBucketPath(pathId, ver)), ByteString(template(pathId).toProtoByteArray))
    case None => Node(repository.path(TemplateBucketPath(pathId)), ByteString.empty)
  }

  /**
    * Raw store methods to create templates, bypassing the repository.
    *
    * @param pathId templates' pathId
    * @param version optionally, template's version
    * @return
    */
  def rawStore(pathId: PathId, version: Option[Int] = Some(1)): Future[String] = store.create(node(pathId, version))
  def rawStore(templates: Seq[(PathId, Option[Int])]): Source[String, NotUsed] = {
    Source.fromIterator(() => templates.iterator)
      .map { case (pathId, version) => node(pathId, version) }
      .via(store.createFlow)
  }

  "TemplateRepository" when {
    "initialising" should {
      "be successful on an empty repository" in {
        When("A repository is initialized with an empty storage")
        repository.init().futureValue shouldBe Done

        Then("It should be successful and counters map should be empty")
        repository.counters.size() shouldBe 0
      }

      "successfully read existing templates" in {
        When("Repository state is empty")
        clear().futureValue

        And("Storage has existing templates")
        val templates = Seq((PathId("/sleep"), Some(1)), (PathId("/foo/bar"), Some(1)), (PathId("/foo/bar"), Some(2)), (PathId("/bazz"), None))
        rawStore(templates).runWith(Sink.ignore).futureValue shouldBe Done

        And("Repository is initialized")
        repository.init().futureValue shouldBe Done

        Then("It should be successful and counters map should have proper values")
        repository.counters.size() shouldBe 2
        repository.counters.get(PathId("/sleep")).intValue shouldBe 1
        repository.counters.get(PathId("/foo/bar")).intValue shouldBe 2
        repository.counters.containsKey(PathId("/bazz")) shouldBe false
      }
    }

    "create" should {
      "create a new template successfully" in {
        When("a new template is created")
        val template = randomTemplate()

        Then("operation should be successful")
        val versioned = repository.create(template).futureValue

        And("return a versioned template")
        versioned shouldBe Versioned(template, 1)

        And("new template should exist in the storage")
        repository.exists(versioned.pathId)

        And("the template counter should contain the created version")
        repository.counters.get(versioned.pathId).intValue() shouldBe 1
      }

      "create two versions of the same template successfully" in {
        When("a new template is created")
        val template = randomTemplate()

        Then("operation should be successful")
        val versioned1 = repository.create(template).futureValue

        And("return a versioned template with version = 1")
        versioned1 shouldBe Versioned(template, 1)

        Then("A new template version is stored")
        val versioned2 = repository.create(template).futureValue

        And("return a versioned template with version = 2")
        versioned2 shouldBe Versioned(template, 2)
      }

      "fail to create a new template with an existing version" in {
        When("a new template is created")
        val template = randomTemplate()

        Then("operation should be successful")
        val versioned = repository.create(template).futureValue
        versioned shouldBe Versioned(template, 1)

        And("the same template is created again an exception is thrown")
        intercept[NodeExistsException] {
          Await.result(repository.create(versioned), Duration.Inf)
        }
      }
    }

    "read" should {
      "read an existing template" in {
        When("a new template is created")
        val template = randomTemplate()
        val versioned = repository.create(template).futureValue

        Then("it can be read and parsed successfully")
        val res = repository.read(versioned.pathId, versioned.version).futureValue
        res shouldBe versioned
      }

      "fail to read an non-existing template" in {
        When("trying to read an non-existing template")
        val pathId = PathId(s"/sleep-${rand.nextInt()}")

        Then("operation should fail")
        intercept[NoNodeException] {
          Await.result(repository.read(pathId, 1), Duration.Inf)
        }
      }
    }

    "delete" should {
      "successfully delete an existing template" in {
        When("a new template is created")
        val template = randomTemplate()
        val versioned = repository.create(template).futureValue

        And("it is deleted")
        repository.delete(versioned.pathId, versioned.version).futureValue shouldBe Done

        Then("the version should not be in the store")
        repository.exists(versioned.pathId, versioned.version).futureValue shouldBe false

        And("but the template itself should")
        repository.exists(versioned.pathId).futureValue shouldBe true
      }

      "successfully delete a non-existing template" in {
        Then("deleting a non-existing template is successful")
        repository.delete(PathId(s"/sleep-${rand.nextInt()}")).futureValue shouldBe Done
      }
    }

    "versions" should {
      "return existing versions for a template" in {
        When("a new template with a few versions is created")
        val pathId = PathId(s"/sleep-${rand.nextInt()}")
        repository.create(Versioned(template(pathId), 1)).futureValue
        repository.create(Versioned(template(pathId), 2)).futureValue

        Then("versions should return existing versions")
        repository.versions(pathId).futureValue should contain theSameElementsAs (Seq(1, 2))
      }

      "return an empty sequence for a template without versions" in {
        When("a new template with a few versions is created")
        val pathId = PathId(s"/sleep-${rand.nextInt()}")
        rawStore(pathId, None).futureValue

        Then("versions should return existing versions")
        repository.versions(pathId).futureValue.isEmpty shouldBe true
      }

      "fail for a non-existing template" in {
        Then("versions should fail for a non-existing template")
        intercept[NoNodeException] {
          Await.result(repository.versions(PathId(s"/sleep-${rand.nextInt()}")), Duration.Inf)
        }
      }
    }

    "exist" should {
      "return true for an existing template without versions" in {
        When("a new template without versions is created")
        val pathId = PathId(s"/sleep-${rand.nextInt()}")
        rawStore(pathId, None).futureValue

        Then("exist should return true for the template version")
        repository.exists(pathId).futureValue shouldBe true
      }

      "return true for and existing template version" in {
        When("a new template is created")
        val template = randomTemplate()
        val versioned = repository.create(template).futureValue

        Then("exist should return true for the template version")
        repository.exists(versioned.pathId, versioned.version).futureValue shouldBe true
      }

      "return false for a non-existing template" in {
        Then("exist should return false for a non-existing template")
        repository.exists(PathId(s"/sleep-${rand.nextInt()}")).futureValue shouldBe false
        repository.exists(PathId(s"/sleep-${rand.nextInt()}")).futureValue shouldBe false
      }
    }
  }

  def clear(): Future[String] = {
    repository.counters.clear()
    store.delete(repository.base)
  }

  /**
    * Helper method to print all templates and their versions from the repo
    */
  def debugPrintVersions() = {
    val versions = Source
      .fromFuture(store.children(repository.base))
      .mapConcat(identity(_))
      .map { t => logger.info(s"template = $t"); t }
      .via(store.childrenFlow)
      .mapConcat(identity(_))
      .via(store.childrenFlow)
      .runWith(Sink.seq)
      .futureValue

    logger.info(s"versions = $versions")
  }
}
