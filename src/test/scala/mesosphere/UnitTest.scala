package mesosphere

import java.util.concurrent.{ LinkedBlockingDeque, TimeUnit }

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.{ TestActor, TestActorRef, TestKitBase }
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.test.Mockito
import org.scalactic.source.Position
import org.scalatest.matchers.{ BeMatcher, MatchResult }
import org.scalatest._
import org.scalatest.concurrent.{ JavaFutures, ScalaFutures, TimeLimitedTests }
import org.scalatest.time.{ Minute, Minutes, Seconds, Span }
import mesosphere.marathon.integration.setup.RestResult

import scala.concurrent.ExecutionContextExecutor

/**
  * Tests which fail due to a known issue can be tagged. They are executed but are marked as canceled when they fail.
  */
case class KnownIssue(jira: String) extends Tag(s"mesosphere.marathon.KnownIssue:$jira")

/**
  * All integration tests should be marked with this tag.
  * Integration tests need a special set up and can take a long time.
  * So it is not desirable, that these kind of tests run every time all the unit tests run.
  */
object IntegrationTag extends Tag("mesosphere.marathon.IntegrationTest")

/**
  * Tag that will conditionally enable a specific test case if an environment variable is set.
  * @param envVarName The name of the environment variable to check if it is set to "true"
  * @param default The default value of the variable.
  * {{{
  *   "Something" should "do something" taggedAs WhenEnvSet("ABC") in {...}
  * }}}
  */
case class WhenEnvSet(envVarName: String, default: String = "false") extends Tag(if (sys.env.getOrElse(envVarName, default) == "true") "" else classOf[Ignore].getName)

trait CancelFailedTestWithKnownIssue extends TestSuite {

  val cancelFailedTestsWithKnownIssue = sys.env.getOrElse("MARATHON_CANCEL_TESTS", "false") == "true"
  val containsJira = """mesosphere\.marathon\.KnownIssue\:(\S+)""".r

  def knownIssue(testData: TestData): Option[String] = testData.tags.collectFirst{ case containsJira(jira) => jira }

  def markAsCanceledOnFailure(jira: String)(blk: => Outcome): Outcome =
    blk match {
      case Failed(ex) => Canceled(s"Known issue $jira: ${ex.getMessage}", ex)
      case other => other
    }

  override def withFixture(test: NoArgTest): Outcome = knownIssue(test) match {
    case Some(jira) if cancelFailedTestsWithKnownIssue => markAsCanceledOnFailure(jira) { super.withFixture(test) }
    case _ => super.withFixture(test)
  }

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
  with BeforeAndAfterAll
  with TimeLimitedTests
  with CancelFailedTestWithKnownIssue {

  private class LoggingInformer(info: Informer) extends Informer {
    def apply(message: String, payload: Option[Any] = None)(implicit pos: Position): Unit = {
      logger.info(s"===== ${message} (${pos.fileName}:${pos.lineNumber}) =====")
      info.apply(message, payload)(pos)
    }
  }

  /**
    * We wrap the informer so that we can see where we are in the test in the logs
    */
  override protected def info: Informer = {
    new LoggingInformer(super.info)
  }
  abstract protected override def runTest(testName: String, args: Args): Status = {
    logger.info(s"=== Test: ${testName} ===")
    super.runTest(testName, args)
  }

  override val timeLimit = Span(1, Minute)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))
}

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
      |akka.test.default-timeout=${patienceConfig.timeout.millisPart}
    """.stripMargin).withFallback(ConfigFactory.load())
  implicit lazy val system: ActorSystem = {
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
  override val timeLimit = Span(15, Minutes)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Seconds))

  /**
    * Custom matcher for HTTP responses that print response body.
    * @param status The expected status code.
    */
  class RestResultMatcher(status: Int) extends BeMatcher[RestResult[_]] {
    def apply(left: RestResult[_]) =
      MatchResult(
        left.code == status,
        s"Response code was not $status but ${left.code} with body '${left.entityString}'",
        s"Response code was $status with body '${left.entityString}'"
      )
  }

  val Accepted = new RestResultMatcher(202)
  val BadGateway = new RestResultMatcher(502)
  val Created = new RestResultMatcher(201)
  val Conflict = new RestResultMatcher(409)
  val Deleted = new RestResultMatcher(202)
  val OK = new RestResultMatcher(200)
  val NotFound = new RestResultMatcher(404)
  val ServerError = new RestResultMatcher(500)
}

abstract class IntegrationTest extends WordSpec with IntegrationTestLike

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike {
  protected override lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
       |akka.test.default-timeout=${patienceConfig.timeout.toMillis}
    """.stripMargin).withFallback(ConfigFactory.load())
}

abstract class AkkaIntegrationTest extends IntegrationTest with AkkaIntegrationTestLike
