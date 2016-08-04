package mesosphere

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
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

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with BeforeAndAfterAll {
  protected lazy val akkaConfig: Config = ConfigFactory.load
  implicit lazy val system = ActorSystem(suiteName, akkaConfig)
  implicit lazy val scheduler = system.scheduler
  implicit lazy val materializer = ActorMaterializer()
  implicit lazy val ctx = system.dispatcher
  implicit val askTimeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  abstract override def afterAll() {
    Await.result(system.terminate(), Duration.Inf)
    super.afterAll
  }
}

abstract class AkkaUnitTest extends WordSpec with AkkaUnitTestLike
