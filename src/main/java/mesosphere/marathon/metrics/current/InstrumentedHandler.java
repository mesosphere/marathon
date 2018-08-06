package mesosphere.marathon.metrics.current;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.RatioGauge;
import com.codahale.metrics.Timer;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.AsyncContextState;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * A Jetty {@link Handler} which records various metrics about an underlying {@link Handler}
 * instance.
 *
 * This is a copy of InstrumentedHandler from Dropwizard metrics, slightly adjusted to support Marathon metric name
 * conventions.
 */
public class InstrumentedHandler extends HandlerWrapper {
    private final MetricRegistry metricRegistry;

    private String name;
    private final String prefix;

    // the requests handled by this handler, excluding active
    private Timer requests;

    // the number of dispatches seen by this handler, excluding active
    private Timer dispatches;

    // the number of active requests
    private LongAdder activeRequests = new LongAdder();

    // the number of active dispatches
    private LongAdder activeDispatches = new LongAdder();

    // the number of requests currently suspended.
    private LongAdder activeSuspended = new LongAdder();

    // the number of requests that have been asynchronously dispatched
    private Meter asyncDispatches;

    // the number of requests that expired while suspended
    private Meter asyncTimeouts;

    private Meter[] responses;

    private Timer getRequests;
    private Timer postRequests;
    private Timer headRequests;
    private Timer putRequests;
    private Timer deleteRequests;
    private Timer optionsRequests;
    private Timer traceRequests;
    private Timer connectRequests;
    private Timer moveRequests;
    private Timer otherRequests;

    private AsyncListener listener;

    /**
     * Create a new instrumented handler using a given metrics registry.
     *
     * @param registry the registry for the metrics
     */
    public InstrumentedHandler(MetricRegistry registry) {
        this(registry, null);
    }

    /**
     * Create a new instrumented handler using a given metrics registry.
     *
     * @param registry the registry for the metrics
     * @param prefix   the prefix to use for the metrics names
     */
    public InstrumentedHandler(MetricRegistry registry, String prefix) {
        this.metricRegistry = registry;
        this.prefix = prefix;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        final String prefix = this.prefix == null ? name(getHandler().getClass(), name) : name(this.prefix, name);

        this.requests = metricRegistry.timer(name(prefix, "http.requests.duration.timer.seconds"));
        this.dispatches = metricRegistry.timer(name(prefix, "debug.http.dispatches.duration.timer.seconds"));

        metricRegistry.gauge(name(prefix, "http.requests.active.gauge"), () -> () -> activeRequests.sum());
        metricRegistry.gauge(name(prefix, "debug.http.dispatches.active.gauge"),
                () -> () -> activeDispatches.sum());
        metricRegistry.gauge(name(prefix, "debug.http.requests.suspended.gauge"), () -> () -> activeSuspended.sum());

        this.asyncDispatches = metricRegistry.meter(name(prefix, "debug.http.dispatches.async.rate"));
        this.asyncTimeouts = metricRegistry.meter(name(prefix, "debug.http.dispatches.async.timeouts.rate"));

        this.responses = new Meter[]{
                metricRegistry.meter(name(prefix, "http.responses.1xx.rate")), // 1xx
                metricRegistry.meter(name(prefix, "http.responses.2xx.rate")), // 2xx
                metricRegistry.meter(name(prefix, "http.responses.3xx.rate")), // 3xx
                metricRegistry.meter(name(prefix, "http.responses.4xx.rate")), // 4xx
                metricRegistry.meter(name(prefix, "http.responses.5xx.rate"))  // 5xx
        };

        this.getRequests = metricRegistry.timer(name(prefix, "http.requests.get.duration.timer.seconds"));
        this.postRequests = metricRegistry.timer(name(prefix, "http.requests.post.duration.timer.seconds"));
        this.headRequests = metricRegistry.timer(name(prefix, "http.requests.head.duration.timer.seconds"));
        this.putRequests = metricRegistry.timer(name(prefix, "http.requests.put.duration.timer.seconds"));
        this.deleteRequests = metricRegistry.timer(name(prefix, "http.requests.delete.duration.timer.seconds"));
        this.optionsRequests = metricRegistry.timer(name(prefix, "http.requests.options.duration.timer.seconds"));
        this.traceRequests = metricRegistry.timer(name(prefix, "http.requests.trace.duration.timer.seconds"));
        this.connectRequests = metricRegistry.timer(name(prefix, "http.requests.connect.duration.timer.seconds"));
        this.moveRequests = metricRegistry.timer(name(prefix, "http.requests.move.duration.timer.seconds"));
        this.otherRequests = metricRegistry.timer(name(prefix, "http.requests.other.duration.timer.seconds"));

        metricRegistry.register(name(prefix, "debug.http.requests.4xx-to-1m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                return Ratio.of(responses[3].getOneMinuteRate(),
                        requests.getOneMinuteRate());
            }
        });

        metricRegistry.register(name(prefix, "debug.http.requests.4xx-to-5m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                return Ratio.of(responses[3].getFiveMinuteRate(),
                        requests.getFiveMinuteRate());
            }
        });

        metricRegistry.register(name(prefix, "debug.http.requests.4xx-to-15m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                return Ratio.of(responses[3].getFifteenMinuteRate(),
                        requests.getFifteenMinuteRate());
            }
        });

        metricRegistry.register(name(prefix, "debug.http.requests.5xx-to-1m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                return Ratio.of(responses[4].getOneMinuteRate(),
                        requests.getOneMinuteRate());
            }
        });

        metricRegistry.register(name(prefix, "debug.http.requests.5xx-to-5m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            protected Ratio getRatio() {
                return Ratio.of(responses[4].getFiveMinuteRate(),
                        requests.getFiveMinuteRate());
            }
        });

        metricRegistry.register(name(prefix, "debug.http.requests.5xx-to-15m-rate-ratio.gauge"), new RatioGauge() {
            @Override
            public Ratio getRatio() {
                return Ratio.of(responses[4].getFifteenMinuteRate(),
                        requests.getFifteenMinuteRate());
            }
        });


        this.listener = new AsyncListener() {
            private long startTime;

            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                asyncTimeouts.mark();
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
                startTime = System.currentTimeMillis();
                event.getAsyncContext().addListener(this);
            }

            @Override
            public void onError(AsyncEvent event) throws IOException {
            }

            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                final AsyncContextState state = (AsyncContextState) event.getAsyncContext();
                final HttpServletRequest request = (HttpServletRequest) state.getRequest();
                final HttpServletResponse response = (HttpServletResponse) state.getResponse();
                updateResponses(request, response, startTime, true);
                if (state.getHttpChannelState().getState() != HttpChannelState.State.DISPATCHED) {
                    activeSuspended.decrement();
                }
            }
        };
    }

    @Override
    public void handle(String path,
                       Request request,
                       HttpServletRequest httpRequest,
                       HttpServletResponse httpResponse) throws IOException, ServletException {

        activeDispatches.increment();

        final long start;
        final HttpChannelState state = request.getHttpChannelState();
        if (state.isInitial()) {
            // new request
            activeRequests.increment();
            start = request.getTimeStamp();
            state.addListener(listener);
        } else {
            // resumed request
            start = System.currentTimeMillis();
            activeSuspended.decrement();
            if (state.getState() == HttpChannelState.State.DISPATCHED) {
                asyncDispatches.mark();
            }
        }

        try {
            super.handle(path, request, httpRequest, httpResponse);
        } finally {
            final long now = System.currentTimeMillis();
            final long dispatched = now - start;

            activeDispatches.decrement();
            dispatches.update(dispatched, TimeUnit.MILLISECONDS);

            if (state.isSuspended()) {
                activeSuspended.increment();
            } else if (state.isInitial()) {
                updateResponses(httpRequest, httpResponse, start, request.isHandled());
            }
            // else onCompletion will handle it.
        }
    }

    private Timer requestTimer(String method) {
        final HttpMethod m = HttpMethod.fromString(method);
        if (m == null) {
            return otherRequests;
        } else {
            switch (m) {
                case GET:
                    return getRequests;
                case POST:
                    return postRequests;
                case PUT:
                    return putRequests;
                case HEAD:
                    return headRequests;
                case DELETE:
                    return deleteRequests;
                case OPTIONS:
                    return optionsRequests;
                case TRACE:
                    return traceRequests;
                case CONNECT:
                    return connectRequests;
                case MOVE:
                    return moveRequests;
                default:
                    return otherRequests;
            }
        }
    }

    private void updateResponses(HttpServletRequest request, HttpServletResponse response, long start, boolean isHandled) {
        final int responseStatus;
        if (isHandled) {
            responseStatus = response.getStatus() / 100;
        } else {
            responseStatus = 4; // will end up with a 404 response sent by HttpChannel.handle
        }
        if (responseStatus >= 1 && responseStatus <= 5) {
            responses[responseStatus - 1].mark();
        }
        activeRequests.decrement();
        final long elapsedTime = System.currentTimeMillis() - start;
        requests.update(elapsedTime, TimeUnit.MILLISECONDS);
        requestTimer(request.getMethod()).update(elapsedTime, TimeUnit.MILLISECONDS);
    }
}
