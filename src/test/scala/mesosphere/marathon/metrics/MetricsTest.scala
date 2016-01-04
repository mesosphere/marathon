package mesosphere.marathon.metrics

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{ ExponentiallyDecayingReservoir, MetricRegistry }
import com.google.inject.{ Guice, AbstractModule }
import com.google.inject.matcher.{ AbstractMatcher, Matchers }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.metrics.Metrics._
import org.aopalliance.intercept.{ MethodInvocation, MethodInterceptor }
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar

class FooBar {
  def dummy(): Unit = {}
}

class MetricsTest
    extends MarathonSpec
    with MockitoSugar {
  private var metrics: Metrics = _

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

  before {
    metrics = new Metrics(new MetricRegistry())
  }

  test("Metrics#className should strip 'EnhancerByGuice' from the metric names") {
    val instance = Guice.createInjector(new TestModule).getInstance(classOf[FooBar])
    assert(instance.getClass.getName.contains("EnhancerByGuice"))

    assert(metrics.className(instance.getClass) == "mesosphere.marathon.metrics.FooBar")
  }

  test("Metrics caches the class names") {
    val metricsSpy = spy(metrics)

    metricsSpy.name("prefix", classOf[FooBar], "method1")
    metricsSpy.name("prefix", classOf[FooBar], "method2")
    metricsSpy.name("prefix", classOf[MetricsTest], "method1")

    verify(metricsSpy, times(1)).stripGuiceMarksFromClassName(classOf[FooBar])
    verify(metricsSpy, times(2)).stripGuiceMarksFromClassName(any())
  }

  test("Metrics#name should use a dot to separate the class name and the method name") {
    val expectedName = "service.mesosphere.marathon.core.task.tracker.TaskTracker.write-request-time"
    val actualName = metrics.name("service", classOf[TaskTracker], "write-request-time")

    assert(expectedName.equals(actualName))
  }

  test("The Histogram wrapper should properly proxy updates") {
    val origHistogram = new com.codahale.metrics.Histogram(new ExponentiallyDecayingReservoir())
    val histogram = new Histogram(origHistogram)

    histogram.update(10L)
    histogram.update(1)

    assert(origHistogram.getSnapshot.getMax == 10)
    assert(origHistogram.getSnapshot.getMin == 1)
  }

  test("The Meter wrapper should properly proxy marks") {
    val origMeter = new com.codahale.metrics.Meter
    val meter = new Meter(origMeter)

    meter.mark()
    meter.mark(10)

    assert(origMeter.getCount == 11)
  }

  test("The Timer wrapper should properly time method calls and proxy the updates") {
    val origTimer = mock[com.codahale.metrics.Timer]
    val timer = new Timer(origTimer)

    timer {}

    val durationCaptor = ArgumentCaptor.forClass(classOf[Long])

    verify(origTimer).update(durationCaptor.capture(), org.mockito.Matchers.eq(TimeUnit.NANOSECONDS))

    assert(durationCaptor.getValue > 0)
  }
}
