package book;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Three real OTLP signals; no in-memory tracing substitute. */
public final class Telemetry implements AutoCloseable {
    public final String service;
    public final OpenTelemetrySdk sdk;
    public final Tracer tracer;
    public final Logger logger;
    public final LongCounter requests;
    public final DoubleHistogram duration;

    public Telemetry(String service) {
        this.service = service;
        String endpoint = System.getenv().getOrDefault("COLLECTOR_URL", "http://127.0.0.1:4318");
        Resource resource = Resource.create(Attributes.builder().put("service.name", service)
                .put("service.version", "1.0.0").put("deployment.environment.name", "book-lab").build());
        double ratio = Double.parseDouble(System.getenv().getOrDefault("SAMPLER_RATIO", "1.0"));
        SdkTracerProvider traces = SdkTracerProvider.builder().setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio)))
                .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint + "/v1/traces").build()).setScheduleDelay(Duration.ofMillis(250)).build()).build();
        SdkMeterProvider metrics = SdkMeterProvider.builder().setResource(resource)
                .registerView(InstrumentSelector.builder().setName("lab.http.duration").build(), View.builder()
                        .setAggregation(Aggregation.explicitBucketHistogram(List.of(.005,.01,.025,.05,.1,.25,.5,1.0,2.0,5.0))).build())
                .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.builder()
                        .setEndpoint(endpoint + "/v1/metrics").build()).setInterval(Duration.ofSeconds(1)).build()).build();
        SdkLoggerProvider logs = SdkLoggerProvider.builder().setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(OtlpHttpLogRecordExporter.builder()
                        .setEndpoint(endpoint + "/v1/logs").build()).setScheduleDelay(Duration.ofMillis(250)).build()).build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(traces).setMeterProvider(metrics).setLoggerProvider(logs)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
        tracer = sdk.getTracer("book.observability", "1.0.0");
        logger = sdk.getLogsBridge().get("book.observability");
        requests = sdk.getMeter("book.observability").counterBuilder("lab.requests").setUnit("{request}").build();
        duration = sdk.getMeter("book.observability").histogramBuilder("lab.http.duration").setUnit("s").build();
        for (String outcome : List.of("ok", "error")) requests.add(0, Attributes.builder().put("route","/orders").put("outcome",outcome).build());
    }
    public void flush() {
        sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
        sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS);
        sdk.getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS);
    }
    public void close() { sdk.close(); }
}
