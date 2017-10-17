import java.io.FileWriter
import sbt._

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import sbt.testing.{Event, OptionalThrowable, Status, TestSelector}
import sbt.{File, TestEvent, TestResult, TestsListener}

import scala.collection.mutable.ListBuffer

class PhabricatorTestReportListener(outputDir: File) extends TestsListener {
  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  @JsonInclude(Include.NON_NULL)
  case class TestCase(name: String, result: String, namespace: String, duration: Float, details: Option[String])

  case class TestSuite(name: String) {
    val events = ListBuffer.empty[Event]

    def testCases: Seq[TestCase] = {
      events.map { e =>
        val testCase = e.selector() match {
          case selector: TestSelector =>
            val name = selector.testName
            if (name.length < 255)
              name
            else
              name.substring(name.length - 255, name.length)
          case _ => "(not a test)"
        }
        val (result, details) = e.status match {
          case Status.Error => ("broken", if (e.throwable().isDefined) Some(e.throwable().get.getMessage) else None)
          case Status.Failure => ("fail", if (e.throwable().isDefined) Some(e.throwable().get.getMessage) else None)
          case Status.Skipped => ("skip", None)
          case Status.Canceled=> ("unsound", if (e.throwable().isDefined) Some(e.throwable().get.getMessage) else None)
          case _ => ("pass", None)
        }
        TestCase(namespace = name, name = testCase, duration = e.duration().toFloat / 1000.0F, result = result, details = details)
      }
    }
  }

  var currentSuite = Option.empty[TestSuite]

  override def startGroup(name: String): Unit = currentSuite = Some(TestSuite(name))

  override def testEvent(event: TestEvent): Unit = {
    currentSuite.foreach(_.events ++= event.detail)
  }

  override def endGroup(name: String, t: Throwable): Unit = {
    val event = new Event {
      def fullyQualifiedName= name
      def duration = -1
      def status  = Status.Error
      def fingerprint = null
      def selector = null
      def throwable = new OptionalThrowable(t)
    }
    currentSuite.foreach(_.events += event)
    endGroup(name, TestResult.Failed)
  }

  override def endGroup(name: String, result: sbt.TestResult.Value): Unit = {
    currentSuite.foreach { suite =>
      mapper.writeValue(new FileWriter(outputDir / s"$name.json"), suite.testCases)
    }
    currentSuite = Option.empty
  }

  override def doComplete(finalResult: TestResult.Value): Unit = {}

  override def doInit(): Unit = outputDir.mkdirs()
}
