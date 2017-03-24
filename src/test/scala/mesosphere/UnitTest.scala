package mesosphere

import akka.testkit.{ TestActor, TestActorRef }
import java.util.concurrent.{ LinkedBlockingDeque, TimeUnit }

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.TestKitBase
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import org.scalatest._
import org.scalatest.concurrent.{ JavaFutures, ScalaFutures }
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.ExecutionContextExecutor

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
case class WhenEnvSet(envVarName: String) extends Tag(if (sys.env.getOrElse(envVarName, "false") == "true") "" else classOf[Ignore].getName)

/**
  * Mixing in this trait will result in retrying a failed test again.
  * If the second run succeeds, the result will be Canceled.
  */
trait RetryOnFailed extends TestSuite with Retries {
  override def withFixture(test: NoArgTest): Outcome = withRetryOnFailure { super.withFixture(test) }
}

trait ValidationClue {
  this: Assertions =>

  def withValidationClue[T](f: => T): T = scala.util.Try { f }.recover {
    // handle RAML validation errors
    case vfe: ValidationFailedException => fail(vfe.failure.violations.toString())
    case th => throw th
  }.get
}

/**
  * Base trait for all unit tests in WordSpec style with common matching/before/after and Option/Try/Future
  * helpers all mixed in.
  */
trait UnitTestLike extends WordSpecLike
    with GivenWhenThen
    with ScalaFutures
    with JavaFutures
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterEach
    with OptionValues
    with TryValues
    with AppendedClues
    with StrictLogging
    with Mockito
    with ExitDisabledTest {

  override def beforeAll(): Unit = {
    Kamon.start()
    super.beforeAll()
  }

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(3, Seconds))
}

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
      |akka.test.default-timeout=${patienceConfig.timeout.millisPart}
    """.stripMargin).withFallback(ConfigFactory.load())
  implicit lazy val system: ActorSystem = {
    Kamon.start()
    ActorSystem(suiteName, akkaConfig)
  }
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
  implicit val askTimeout: Timeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  def newTestActor() =
    TestActorRef[TestActor](TestActor.props(new LinkedBlockingDeque()))

  abstract override def afterAll(): Unit = {
    super.afterAll()
    // intentionally shutdown the actor system last.
    system.terminate().futureValue
  }
}

abstract class AkkaUnitTest extends UnitTest with AkkaUnitTestLike

trait IntegrationTestLike extends UnitTestLike {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(90, Seconds), interval = Span(2, Seconds))
}

abstract class IntegrationTest extends WordSpec with IntegrationTestLike with RetryOnFailed

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike {
  protected override lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
       |akka.test.default-timeout=${patienceConfig.timeout.millisPart}
    """.stripMargin).withFallback(ConfigFactory.load())
}

abstract class AkkaIntegrationTest extends IntegrationTest with AkkaIntegrationTestLike

