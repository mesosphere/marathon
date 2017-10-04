package mesosphere.marathon
package metrics

import com.google.inject.matcher.{ AbstractMatcher, Matchers }
import com.google.inject.{ AbstractModule, Guice }
import mesosphere.UnitTest
import mesosphere.marathon.core.task.tracker.InstanceTracker
import org.aopalliance.intercept.{ MethodInterceptor, MethodInvocation }

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

  "Metrics" should {
    "Metrics#className should strip 'EnhancerByGuice' from the metric names" in {
      val instance = Guice.createInjector(new TestModule).getInstance(classOf[FooBar])
      assert(instance.getClass.getName.contains("EnhancerByGuice"))

      assert(metrics.className(instance.getClass) == "mesosphere.marathon.metrics.FooBar")
    }

    "Metrics#name should replace $ with ." in {
      val instance = new Serializable {}
      assert(instance.getClass.getName.contains('$'))

      assert(metrics.name(ServiceMetric, instance.getClass, "test$method") ==
        s"${ServiceMetric.name}.mesosphere.marathon.metrics.MetricsTest.anon.1.test.method")
    }

    "Metrics#name should use a dot to separate the class name and the method name" in {
      val expectedName = "service.mesosphere.marathon.core.task.tracker.InstanceTracker.write-request-time"
      val actualName = metrics.name(ServiceMetric, classOf[InstanceTracker], "write-request-time")

      assert(expectedName.equals(actualName))
    }
  }
}
