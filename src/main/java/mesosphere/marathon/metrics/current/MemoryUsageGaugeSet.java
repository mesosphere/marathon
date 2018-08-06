package mesosphere.marathon.metrics.current;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.RatioGauge;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * A set of gauges for JVM memory usage, including stats on heap vs. non-heap memory, plus
 * GC-specific memory pools.
 *
 * This is a copy of MemoryUsageGaugeSet from Dropwizard metrics, slightly adjusted to support Marathon metric name
 * conventions.
 */
public class MemoryUsageGaugeSet implements MetricSet {
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]+");

    private final MemoryMXBean mxBean;
    private final List<MemoryPoolMXBean> memoryPools;

    public MemoryUsageGaugeSet() {
        this(ManagementFactory.getMemoryMXBean(), ManagementFactory.getMemoryPoolMXBeans());
    }

    public MemoryUsageGaugeSet(MemoryMXBean mxBean,
                               Collection<MemoryPoolMXBean> memoryPools) {
        this.mxBean = mxBean;
        this.memoryPools = new ArrayList<>(memoryPools);
    }

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new HashMap<>();

        gauges.put("total.init.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getInit() +
                mxBean.getNonHeapMemoryUsage().getInit());
        gauges.put("total.used.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getUsed() +
                mxBean.getNonHeapMemoryUsage().getUsed());
        gauges.put("total.max.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getMax() +
                mxBean.getNonHeapMemoryUsage().getMax());
        gauges.put("total.committed.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getCommitted() +
                mxBean.getNonHeapMemoryUsage().getCommitted());

        gauges.put("heap.init.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getInit());
        gauges.put("heap.used.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getUsed());
        gauges.put("heap.max.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getMax());
        gauges.put("heap.committed.gauge.bytes", (Gauge<Long>) () -> mxBean.getHeapMemoryUsage().getCommitted());
        gauges.put("heap.usage.gauge", new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                final MemoryUsage usage = mxBean.getHeapMemoryUsage();
                return Ratio.of(usage.getUsed(), usage.getMax());
            }
        });

        gauges.put("non-heap.init.gauge.bytes", (Gauge<Long>) () -> mxBean.getNonHeapMemoryUsage().getInit());
        gauges.put("non-heap.used.gauge.bytes", (Gauge<Long>) () -> mxBean.getNonHeapMemoryUsage().getUsed());
        gauges.put("non-heap.max.gauge.bytes", (Gauge<Long>) () -> mxBean.getNonHeapMemoryUsage().getMax());
        gauges.put("non-heap.committed.gauge.bytes", (Gauge<Long>) () -> mxBean.getNonHeapMemoryUsage().getCommitted());
        gauges.put("non-heap.usage.gauge", new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                final MemoryUsage usage = mxBean.getNonHeapMemoryUsage();
                return Ratio.of(usage.getUsed(), usage.getMax());
            }
        });

        for (final MemoryPoolMXBean pool : memoryPools) {
            final String poolName = name("pools",
                    WHITESPACE.matcher(pool.getName()).replaceAll("-").toLowerCase(Locale.US));

            gauges.put(name(poolName, "usage.gauge"), new RatioGauge() {
                @Override
                protected Ratio getRatio() {
                    MemoryUsage usage = pool.getUsage();
                    return Ratio.of(usage.getUsed(),
                            usage.getMax() == -1 ? usage.getCommitted() : usage.getMax());
                }
            });

            gauges.put(name(poolName, "max.gauge.bytes"), (Gauge<Long>) () -> pool.getUsage().getMax());
            gauges.put(name(poolName, "used.gauge.bytes"), (Gauge<Long>) () -> pool.getUsage().getUsed());
            gauges.put(name(poolName, "committed.gauge.bytes"),
                    (Gauge<Long>) () -> pool.getUsage().getCommitted());

            // Only register GC usage metrics if the memory pool supports usage statistics.
            if (pool.getCollectionUsage() != null) {
                gauges.put(name(poolName, "used-after-gc.gauge.bytes"), (Gauge<Long>) () ->
                        pool.getCollectionUsage().getUsed());
            }

            gauges.put(name(poolName, "init.gauge.bytes"), (Gauge<Long>) () -> pool.getUsage().getInit());
        }

        return Collections.unmodifiableMap(gauges);
    }
}
