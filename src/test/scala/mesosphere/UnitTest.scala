package mesosphere

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, BeforeAndAfterEach, Matchers, OptionValues, TryValues, WordSpec, WordSpecLike }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * Base trait for newer unit tests using WordSpec style with common matching/before/after and Option/Try/Future
  * helpers all mixed in.
  */
trait UnitTestLike extends WordSpecLike
  with FutureTestSupport
  with Matchers
  with BeforeAndAfter
  with BeforeAndAfterEach
  with OptionValues
  with TryValues

trait UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with BeforeAndAfterAll {
  protected def config: Config = ConfigFactory.load
  implicit val system = ActorSystem(suiteName, config)

  abstract override def afterAll {
    Await.result(system.terminate(), Duration.Inf)
    super.afterAll
  }
}

trait AkkaUnitTest extends WordSpec with AkkaUnitTestLike
