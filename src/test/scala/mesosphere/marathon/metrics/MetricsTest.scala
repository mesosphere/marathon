package mesosphere.marathon
package metrics

import com.google.inject.matcher.{AbstractMatcher, Matchers}
import com.google.inject.AbstractModule
import mesosphere.UnitTest
import mesosphere.marathon.metrics.current.{DropwizardMetrics, UnitOfMeasurement}
import org.aopalliance.intercept.{MethodInterceptor, MethodInvocation}

class FooBar {
  def dummy(): Unit = {}
}

class MetricsTest extends UnitTest {

  class TestModule extends AbstractModule {
    class DummyBehavior extends MethodInterceptor {
      override def invoke(invocation: MethodInvocation): AnyRef = {
        invocation.proceed()
      }
    }

    object MarathonMatcher extends AbstractMatcher[Class[_]] {
      override def matches(t: Class[_]): Boolean = t == classOf[FooBar]
    }

    override def configure(): Unit = {
      bindInterceptor(Matchers.any(), Matchers.any(), new DummyBehavior())
    }
  }

  "DropwizardMetrics.constructName" should {
    "not append a unit of measurement suffix, when none is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.None)
      name shouldBe "marathon.metric.counter"
    }

    "append the memory unit of measurement suffix, when it is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.Memory)
      name shouldBe "marathon.metric.counter.bytes"
    }

    "append the time unit of measurement suffix, when it is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.Time)
      name shouldBe "marathon.metric.counter.seconds"
    }

    "throw an exception if metric name components contain a disallowed character" in {
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon#", "metric", "counter", UnitOfMeasurement.None)
      }
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon", "metric$", "counter", UnitOfMeasurement.None)
      }
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon", "metric", "counter%", UnitOfMeasurement.None)
      }
    }

  }

}
