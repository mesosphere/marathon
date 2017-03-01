package mesosphere.marathon
package metrics

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{ ExponentiallyDecayingReservoir, MetricRegistry }
import com.google.inject.matcher.{ AbstractMatcher, Matchers }
import com.google.inject.{ AbstractModule, Guice }
import mesosphere.UnitTest
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics._
import org.aopalliance.intercept.{ MethodInterceptor, MethodInvocation }
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._

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

  case class Fixture(metrics: Metrics = new Metrics(new MetricRegistry))

  "Metrics" should {
    "Metrics#className should strip 'EnhancerByGuice' from the metric names" in new Fixture {
      val instance = Guice.createInjector(new TestModule).getInstance(classOf[FooBar])
      assert(instance.getClass.getName.contains("EnhancerByGuice"))

      assert(metrics.className(instance.getClass) == "mesosphere.marathon.metrics.FooBar")
    }

    "Metrics#name should replace $ with ." in new Fixture {
      val instance = new Serializable {}
      assert(instance.getClass.getName.contains('$'))

      assert(metrics.name("test$prefix", instance.getClass, "test$method") ==
        "test.prefix.mesosphere.marathon.metrics.MetricsTest.anonfun.1.anonfun.apply.mcV.sp.2.anon.2.anon.7.test.method")
    }

    "Metrics caches the class names" in new Fixture {
      val metricsSpy = spy(metrics)

      metricsSpy.name("prefix", classOf[FooBar], "method1")
      metricsSpy.name("prefix", classOf[FooBar], "method2")
      metricsSpy.name("prefix", classOf[MetricsTest], "method1")

      verify(metricsSpy, times(1)).stripGuiceMarksFromClassName(classOf[FooBar])
      verify(metricsSpy, times(2)).stripGuiceMarksFromClassName(any)
    }

    "Metrics#name should use a dot to separate the class name and the method name" in new Fixture {
      val expectedName = "service.mesosphere.marathon.core.task.tracker.InstanceTracker.write-request-time"
      val actualName = metrics.name("service", classOf[InstanceTracker], "write-request-time")

      assert(expectedName.equals(actualName))
    }

    "The Histogram wrapper should properly proxy updates" in new Fixture {
      val origHistogram = new com.codahale.metrics.Histogram(new ExponentiallyDecayingReservoir())
      val histogram = new Histogram(origHistogram)

      histogram.update(10L)
      histogram.update(1)

      assert(origHistogram.getSnapshot.getMax == 10)
      assert(origHistogram.getSnapshot.getMin == 1)
    }

    "The Meter wrapper should properly proxy marks" in {
      val origMeter = new com.codahale.metrics.Meter
      val meter = new Meter(origMeter)

      meter.mark()
      meter.mark(10)

      assert(origMeter.getCount == 11)
    }

    "The Timer wrapper should properly time method calls and proxy the updates" in new Fixture {
      val origTimer = mock[com.codahale.metrics.Timer]
      val timer = new Timer(origTimer)

      timer {}

      val durationCaptor = ArgumentCaptor.forClass(classOf[Long])

      verify(origTimer).update(durationCaptor.capture(), org.mockito.Matchers.eq(TimeUnit.NANOSECONDS))

      assert(durationCaptor.getValue > 0)
    }
  }
}
