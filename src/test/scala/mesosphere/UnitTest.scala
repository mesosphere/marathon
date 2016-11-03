package mesosphere

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import mesosphere.marathon.{ IntegrationTest => AnnotatedIntegrationTest }
import org.scalatest.{ AppendedClues, BeforeAndAfter, BeforeAndAfterAll, BeforeAndAfterEach, FunSuite, FunSuiteLike, GivenWhenThen, Matchers, OptionValues, Suite, TryValues, WordSpec, WordSpecLike }

/**
  * Base trait for newer unit tests using WordSpec style with common matching/before/after and Option/Try/Future
  * helpers all mixed in.
  */
trait UnitTestLike extends WordSpecLike
  with FutureTestSupport
  with GivenWhenThen
  with Matchers
  with BeforeAndAfter
  with BeforeAndAfterEach
  with OptionValues
  with TryValues
  with AppendedClues
  with StrictLogging
  with Mockito
  with ExitDisabledTest

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaTest extends Suite with BeforeAndAfterAll with FutureTestSupport with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.load
  implicit lazy val system = ActorSystem(suiteName, akkaConfig)
  implicit lazy val scheduler = system.scheduler
  implicit lazy val mat = ActorMaterializer()
  implicit lazy val ctx = system.dispatcher
  implicit val askTimeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  abstract override def afterAll(): Unit = {
    system.terminate().futureValue
    super.afterAll()
  }
}

@AnnotatedIntegrationTest
trait IntegrationTestLike extends UnitTestLike with IntegrationFutureTestSupport

abstract class IntegrationTest extends UnitTest with IntegrationTestLike

trait AkkaUnitTestLike extends UnitTestLike with AkkaTest

abstract class AkkaUnitTest extends WordSpec with AkkaUnitTestLike

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike

abstract class AkkaIntegrationTest extends AkkaUnitTest with AkkaIntegrationTestLike

/** Support for the older [[mesosphere.marathon.test.MarathonSpec]] style, but with more tooling included */
trait FunTestLike extends FunSuiteLike
  with FutureTestSupport
  with GivenWhenThen
  with Matchers
  with BeforeAndAfter
  with BeforeAndAfterEach
  with OptionValues
  with TryValues
  with AppendedClues
  with StrictLogging
  with Mockito
  with ExitDisabledTest

abstract class FunTest extends FunSuite with FunTestLike

@AnnotatedIntegrationTest
trait IntegrationFunTestLike extends FunTestLike with IntegrationFutureTestSupport

abstract class IntegrationFunTest extends FunTest with IntegrationFunTestLike

trait AkkaFunTestLike extends FunTestLike with AkkaTest

abstract class AkkaFunTest extends FunTest with AkkaFunTestLike

trait AkkaIntegrationFunTestLike extends AkkaFunTestLike with IntegrationFunTestLike

abstract class AkkaIntegrationFunTest extends AkkaFunTest with AkkaIntegrationFunTestLike
