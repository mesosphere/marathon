package mesosphere

import java.util.concurrent.TimeUnit

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.test.Mockito
import org.scalactic.source.Position
import org.scalatest._

import scala.concurrent.ExecutionContextExecutor

/**
  * Tests which are still unreliable should be marked with this tag until
  * they sufficiently pass on master. Prefer this over ignored.
  */
object Unstable extends Tag("mesosphere.marathon.UnstableTest")

/**
  * All integration tests should be marked with this tag.
  * Integration tests need a special set up and can take a long time.
  * So it is not desirable, that these kind of tests run every time all the unit tests run.
  */
object IntegrationTag extends Tag("mesosphere.marathon.IntegrationTest")

/**
  * All time-sensitive integration tests should be marked with this tag.
  *
  * Some integrations are time dependent and excessive resource contention has been known to introduce probabilistic
  * failure.
  */
object SerialIntegrationTag extends Tag("mesosphere.marathon.SerialIntegrationTest")

/**
  * Tag that will conditionally enable a specific test case if an environment variable is set.
  * @param envVarName The name of the environment variable to check if it is set to "true"
  * {{{
  *   "Something" should "do something" taggedAs WhenEnvSet("ABC") in {...}
  * }}}
  */
case class WhenEnvSet(envVarName: String) extends Tag(if (sys.env.getOrElse(envVarName, "true") == "true") "" else classOf[Ignore].getName)

/**
  * Mixing in this trait will result in retrying a failed test again.
  * If the second run succeeds, the result will be Canceled.
  */
trait RetryOnFailed extends TestSuite with Retries {
  override def withFixture(test: NoArgTest): Outcome = withRetryOnFailure { super.withFixture(test) }
}

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
  with BeforeAndAfterAll
  with OptionValues
  with TryValues
  with AppendedClues
  with StrictLogging
  with Mockito

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaTest extends Suite with BeforeAndAfterAll with FutureTestSupport with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.load
  implicit lazy val system = ActorSystem(suiteName, akkaConfig)
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat = ActorMaterializer()
  implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
  implicit val askTimeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  abstract override def afterAll(): Unit = {
    super.afterAll()
    // intentionally shutdown the actor system last.
    system.terminate().futureValue
  }
}

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
  with BeforeAndAfterAll
  with OptionValues
  with TryValues
  with AppendedClues
  with StrictLogging
  with Mockito

abstract class FunTest extends FunSuite with FunTestLike

trait IntegrationFunTestLike extends FunTestLike with IntegrationFutureTestSupport

abstract class IntegrationFunTest extends FunTest with IntegrationFunTestLike

trait AkkaFunTestLike extends FunTestLike with AkkaTest

abstract class AkkaFunTest extends FunTest with AkkaFunTestLike

trait AkkaIntegrationFunTestLike extends AkkaFunTestLike with IntegrationFunTestLike

abstract class AkkaIntegrationFunTest extends AkkaFunTest with AkkaIntegrationFunTestLike

/**
  * Trait for enabling or disabling test suites based on environment variables.
  * There doesn't appear to be an easy way to do this for [[UnitTestLike]],
  * so those test cases can be done like:
  * {{{
  * "Something" should "do" taggedAs WhenEnvSet("ENV_VAR") in {...}
  * }}}
  *
  * This mixin will enable the environment variable conditional for the entire suite.
  */
trait EnvironmentFunTest extends FunTestLike {
  def envVar: String

  override protected def test(testName: String, testTags: Tag*)(testFun: => Any)(implicit pos: Position): Unit = {
    if (sys.env.getOrElse(envVar, "false") == "true") {
      super.test(testName, testTags: _*)(testFun)
    } else {
      super.ignore(testName, testTags: _*)(testFun)
    }
  }
}

