package mesosphere.marathon.metrics.current;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jvm.ThreadDeadlockDetector;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * A set of gauges for the number of threads in their various states and deadlock detection.
 *
 * This is a copy of ThreadStatesGaugeSet from Dropwizard metrics, slightly adjusted to support Marathon metric name
 * conventions.
 */
public class ThreadStatesGaugeSet implements MetricSet {

    // do not compute stack traces.
    private final static int STACK_TRACE_DEPTH = 0;

    private final ThreadMXBean threads;
    private final ThreadDeadlockDetector deadlockDetector;

    /**
     * Creates a new set of gauges using the default MXBeans.
     */
    public ThreadStatesGaugeSet() {
        this(ManagementFactory.getThreadMXBean(), new ThreadDeadlockDetector());
    }

    /**
     * Creates a new set of gauges using the given MXBean and detector.
     *
     * @param threads          a thread MXBean
     * @param deadlockDetector a deadlock detector
     */
    public ThreadStatesGaugeSet(ThreadMXBean threads,
                                ThreadDeadlockDetector deadlockDetector) {
        this.threads = threads;
        this.deadlockDetector = deadlockDetector;
    }

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new HashMap<>();

        for (final Thread.State state : Thread.State.values()) {
            String stateName = state.toString().toLowerCase().replace('_', '-');
            gauges.put(name(stateName, "gauge"),
                    (Gauge<Integer>) () -> getThreadCount(state));
        }

        gauges.put("active.gauge", (Gauge<Integer>) threads::getThreadCount);
        gauges.put("daemon.gauge", (Gauge<Integer>) threads::getDaemonThreadCount);
        gauges.put("deadlocked.gauge", (Gauge<Integer>) () -> deadlockDetector.getDeadlockedThreads().size());

        return Collections.unmodifiableMap(gauges);
    }

    private Integer getThreadCount(Thread.State state) {
        final ThreadInfo[] allThreads = getThreadInfo();
        int count = 0;
        for (ThreadInfo info : allThreads) {
            if (info != null && info.getThreadState() == state) {
                count++;
            }
        }
        return count;
    }

    ThreadInfo[] getThreadInfo() {
        return threads.getThreadInfo(threads.getAllThreadIds(), STACK_TRACE_DEPTH);
    }

}
