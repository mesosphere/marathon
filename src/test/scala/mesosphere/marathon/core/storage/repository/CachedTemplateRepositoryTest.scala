package mesosphere.marathon
package core.storage.repository

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.github.blemale.scaffeine.{LoadingCache, Scaffeine}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.{Spec, Template}
import mesosphere.marathon.core.storage.repository.impl.CachedTemplateRepository
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.{AsyncCuratorBuilderFactory, ZooKeeperPersistenceStore}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.util.ZookeeperServerTest
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.Random

class CachedTemplateRepositoryTest
  extends UnitTest
  with ZookeeperServerTest
  with StrictLogging {

  import CachedTemplateRepository._

  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  lazy val client: CuratorFramework = zkClient(namespace = Some("test")).client
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(client)
  lazy val metrics: Metrics = DummyMetrics
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(metrics, factory, parallelism = 1)

  lazy val repository: CachedTemplateRepository = new CachedTemplateRepository(store)

  val rand = new Random()

  import mesosphere.marathon.core.storage.repository.impl.TemplateRepository._

  def randomPathId() = PathId(s"/test-${rand.nextInt}")
  def spec(pathId: PathId): Spec = AppDefinition(id = pathId)
  def randomSpec(): Spec = spec(randomPathId)

  def node(template: Spec, version: Int) = Node(repository.path(VersionBucketPath(template.id, version)), ByteString(template.toProtoByteArray))

  "Cache" should {
    "be created from Scaffeine builder" in {

      import scala.concurrent.duration._

      val cache: LoadingCache[Int, String] =
        Scaffeine()
          .recordStats()
          .expireAfterWrite(1.hour)
          .maximumSize(500)
          .build{ (i: Int) =>
            if (i < 3) {
              logger.warn(s"Fetching element key=$i")
              s"foo$i"
            } else throw new NoSuchElementException("BOOM")
          }

      cache.get(1) shouldBe "foo1"
      cache.get(1) shouldBe "foo1"
      cache.getIfPresent(1) shouldBe Some("foo1")

      cache.put(1, "foo")
      cache.getIfPresent(1) shouldBe Some("foo")

      cache.getIfPresent(2) shouldBe None
      cache.get(2) shouldBe "foo2"

      intercept[NoSuchElementException] {
        cache.get(3)
      }
    }
  }

  "CachedTemplateRepository" when {

    "create" should {
      "put created template into the cache" in {
        When("a new template is created")
        val spec = randomSpec()
        val template = repository.create(spec).futureValue

        And("new template should be present in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(template)) shouldBe Some(template)
      }
    }

    "read" should {
      "read a template from the cache if exists" in {
        When("a new template is created")
        val spec = randomSpec()
        val template = repository.create(spec).futureValue

        Then("it should be present in the cache")
        repository.cache.synchronous.getIfPresent(toCacheKey(template.pathId, template.version)) shouldBe Some(template)
      }

      "load a template from the store if not in cache" in {
        When("a new template is created directly in the store")
        val spec = randomSpec()
        val pathId = spec.id
        repository.store.create(node(spec, 1)).futureValue

        And("it is not in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(pathId, 1)) shouldBe None

        And("it is read from the repository")
        val template = repository.read(pathId, 1).futureValue

        Then("it should be fetched from the repository successfully")
        template shouldBe Template(spec, 1)

        And("now it should be present in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(pathId, 1)) shouldBe Some(template)
      }

      "fail to read a non-existing template" in {
        When("a non-existing template is read from the repository")
        val pathId = randomPathId()

        Then("operation should fail")
        intercept[NoNodeException] {
          Await.result(repository.read(pathId, 1), Duration.Inf)
        }

        And("the value should NOT be present in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(pathId, 1)) shouldBe None
      }
    }

    "delete" should {
      "delete a template version from the cache and store" in {
        When("a new template is created")
        val spec = randomSpec()
        val template = repository.create(spec).futureValue

        And("deleted")
        repository.delete(template.pathId, template.version).futureValue shouldBe Done

        Then("it should NOT be present in the store")
        repository.exists(template.pathId, template.version).futureValue shouldBe false

        And("and also NOT in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(template)) shouldBe None
      }

      "delete all template versions from the cache and store" in {
        When("mulitple versions of the template are created")
        val spec = randomSpec()

        val template1 = repository.create(spec).futureValue
        val template2 = repository.create(spec).futureValue

        And("deleted")
        repository.delete(template1.pathId).futureValue shouldBe Done

        Then("it should NOT be present in the store")
        repository.exists(template1.pathId).futureValue shouldBe false
        repository.exists(template2.pathId).futureValue shouldBe false

        And("and also NOT in the cache")
        repository.cache.synchronous().getIfPresent(toCacheKey(template1)) shouldBe None
        repository.cache.synchronous().getIfPresent(toCacheKey(template2)) shouldBe None
      }
    }
  }
}
