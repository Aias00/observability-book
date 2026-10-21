package book;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import redis.clients.jedis.JedisPooled;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

/** A deliberately small real HTTP -> Redis -> RocketMQ -> Redis system. */
public final class SystemLab {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Telemetry GATEWAY = new Telemetry("book-gateway");
    static final Telemetry ORDERS = new Telemetry("book-orders");
    static final Telemetry WORKER = new Telemetry("book-worker");
    static final JedisPooled REDIS = new JedisPooled(System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"), 6379);
    static final DefaultMQProducer PRODUCER = new DefaultMQProducer("otel-book-producer");
    static final DefaultMQPushConsumer CONSUMER = new DefaultMQPushConsumer("otel-book-worker");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final ConcurrentLinkedDeque<Map<String,Object>> EVENTS = new ConcurrentLinkedDeque<>();
    static final String TOPIC = "otel-book-orders";
    static final TextMapGetter<HttpExchange> HTTP_GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(HttpExchange exchange) { return exchange.getRequestHeaders().keySet(); }
        public String get(HttpExchange exchange, String key) { return exchange.getRequestHeaders().getFirst(key); }
    };
    static final TextMapGetter<MessageExt> MQ_GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(MessageExt message) { return message.getProperties().keySet(); }
        public String get(MessageExt message, String key) { return message.getProperty(key); }
    };
    static byte[] bytes(Object value) throws Exception { return JSON.writeValueAsBytes(value); }
    static void respond(HttpExchange exchange, int status, Object body) throws Exception {
        byte[] raw = bytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Trace-Id", Span.current().getSpanContext().getTraceId());
        exchange.sendResponseHeaders(status, raw.length);
        exchange.getResponseBody().write(raw);
    }
    static void event(Telemetry telemetry, String name, String id) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("timestamp", Instant.now().toString());row.put("service",telemetry.service);row.put("event",name);
        row.put("order_id",id);row.put("trace_id",Span.current().getSpanContext().getTraceId());
        row.put("span_id",Span.current().getSpanContext().getSpanId());row.put("sampled",Span.current().getSpanContext().isSampled());
        EVENTS.add(row);while(EVENTS.size()>300)EVENTS.pollFirst();
        try { System.out.println(JSON.writeValueAsString(row)); } catch(Exception error) { throw new RuntimeException(error); }
        telemetry.logger.logRecordBuilder().setContext(Context.current()).setSeverity(Severity.INFO)
                .setBody(name).setAttribute("order.id",id).emit();
    }
    static int delay(JsonNode body, String name) {
        int value=body.path(name).asInt(0);
        if(value<0||value>1500)throw new IllegalArgumentException("Delay outside 0..1500 ms");
        return value;
    }
    static void consume(MessageExt message) throws Exception {
        Context parent = WORKER.sdk.getPropagators().getTextMapPropagator().extract(Context.root(),message,MQ_GETTER);
        Span span = WORKER.tracer.spanBuilder("rocketmq process " + TOPIC).setSpanKind(SpanKind.CONSUMER)
                .setParent(parent).setAttribute("messaging.system","rocketmq").setAttribute("messaging.destination.name",TOPIC).startSpan();
        long start=System.nanoTime();
        try(Scope scope=span.makeCurrent()) {
            JsonNode body=JSON.readTree(message.getBody());String id=body.path("id").asText();
            span.setAttribute("order.id",id);Thread.sleep(body.path("worker_delay_ms").asInt());
            Span redis=WORKER.tracer.spanBuilder("redis SET processed").setSpanKind(SpanKind.CLIENT).startSpan();
            try(Scope ignored=redis.makeCurrent()) {REDIS.setex("otelbook:processed:"+id,3600,JSON.writeValueAsString(Map.of("id",id,"trace_id",span.getSpanContext().getTraceId())));}
            finally {redis.end();}
            event(WORKER,"worker.processed",id);
            WORKER.requests.add(1,Attributes.builder().put("route","message").put("outcome","ok").build());
        } catch(Exception error) {span.recordException(error);span.setStatus(StatusCode.ERROR);throw error;}
        finally {WORKER.duration.record((System.nanoTime()-start)/1e9,Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("route"),"message"));span.end();}
    }
    static void order(HttpExchange exchange) {
        Context parent=ORDERS.sdk.getPropagators().getTextMapPropagator().extract(Context.root(),exchange,HTTP_GETTER);
        Span span=ORDERS.tracer.spanBuilder("orders " + exchange.getRequestMethod() + " /orders").setParent(parent)
                .setSpanKind(SpanKind.SERVER).setAttribute("http.route","/orders").startSpan();
        long start=System.nanoTime();String outcome="ok";
        try(Scope scope=span.makeCurrent()) {
            if(exchange.getRequestMethod().equals("GET")) {
                String id=exchange.getRequestURI().getPath().replaceFirst("^/orders/","");
                Span redis=ORDERS.tracer.spanBuilder("redis GET order").setSpanKind(SpanKind.CLIENT).startSpan();
                String value;try(Scope ignored=redis.makeCurrent()){value=REDIS.get("otelbook:order:"+id);}finally{redis.end();}
                respond(exchange,value==null?404:200,Map.of("order_id",id,"found",value!=null));return;
            }
            JsonNode body=JSON.readTree(exchange.getRequestBody());String id=body.path("id").asText();
            if(!id.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Use a short lowercase id");
            int redisDelay=delay(body,"redis_delay_ms"),workerDelay=delay(body,"worker_delay_ms");span.setAttribute("order.id",id);
            if(body.path("fail").asBoolean(false)) {outcome="error";span.setStatus(StatusCode.ERROR,"injected application failure");event(ORDERS,"order.failed",id);respond(exchange,503,Map.of("error","injected-application-error","trace_id",span.getSpanContext().getTraceId()));return;}
            Span redis=ORDERS.tracer.spanBuilder("redis SET order").setSpanKind(SpanKind.CLIENT)
                    .setAttribute("db.system","redis").setAttribute("fault.injected_delay_ms",redisDelay).startSpan();
            try(Scope ignored=redis.makeCurrent()){Thread.sleep(redisDelay);REDIS.setex("otelbook:order:"+id,3600,body.toString());}finally{redis.end();}
            Span producer=ORDERS.tracer.spanBuilder("rocketmq publish " + TOPIC).setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system","rocketmq").setAttribute("messaging.destination.name",TOPIC).startSpan();
            try(Scope ignored=producer.makeCurrent()) {
                Message message=new Message(TOPIC,"created",id,bytes(Map.of("id",id,"worker_delay_ms",workerDelay)));
                if(body.path("propagate").asBoolean(true)) ORDERS.sdk.getPropagators().getTextMapPropagator()
                        .inject(Context.current(),message,(carrier,key,value)->carrier.putUserProperty(key,value));
                PRODUCER.send(message);
            } finally {producer.end();}
            event(ORDERS,"order.queued",id);respond(exchange,202,Map.of("order_id",id,"state","queued","trace_id",span.getSpanContext().getTraceId()));
        } catch(Exception error) {error.printStackTrace();outcome="error";span.recordException(error);span.setStatus(StatusCode.ERROR);try{respond(exchange,500,Map.of("error",error.getClass().getSimpleName()));}catch(Exception ignored){}}
        finally {ORDERS.requests.add(1,Attributes.builder().put("route","/orders").put("outcome",outcome).build());ORDERS.duration.record((System.nanoTime()-start)/1e9,Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("route"),"/orders"));span.end();exchange.close();}
    }
    static void gateway(HttpExchange exchange) {
        try {
            if(exchange.getRequestURI().getPath().equals("/health")){respond(exchange,200,Map.of("status","ready"));exchange.close();return;}
            if(exchange.getRequestURI().getPath().equals("/lab/events")){respond(exchange,200,List.copyOf(EVENTS));exchange.close();return;}
            if(exchange.getRequestURI().getPath().equals("/lab/flush")){GATEWAY.flush();ORDERS.flush();WORKER.flush();respond(exchange,200,Map.of("flushed",true));exchange.close();return;}
        } catch(Exception error){exchange.close();return;}
        Context parent=GATEWAY.sdk.getPropagators().getTextMapPropagator().extract(Context.root(),exchange,HTTP_GETTER);
        Span span=GATEWAY.tracer.spanBuilder("gateway " + exchange.getRequestMethod() + " /orders").setSpanKind(SpanKind.SERVER)
                .setParent(parent).setAttribute("http.route","/orders").startSpan();long start=System.nanoTime();String outcome="ok";
        try(Scope scope=span.makeCurrent()) {
            Span client=GATEWAY.tracer.spanBuilder("gateway -> orders").setSpanKind(SpanKind.CLIENT).startSpan();
            HttpResponse<String> response;
            try(Scope ignored=client.makeCurrent()) {
                byte[] body=exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081"+exchange.getRequestURI()))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                        .method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(body));
                GATEWAY.sdk.getPropagators().getTextMapPropagator().inject(Context.current(),request,(carrier,key,value)->carrier.header(key,value));
                response=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()>=500)client.setStatus(StatusCode.ERROR);
            } finally {client.end();}
            if(response.statusCode()>=500){span.setStatus(StatusCode.ERROR);outcome="error";}
            JsonNode json=JSON.readTree(response.body());event(GATEWAY,"gateway.request",json.path("order_id").asText("unknown"));respond(exchange,response.statusCode(),json);
        } catch(Exception error){span.recordException(error);span.setStatus(StatusCode.ERROR);outcome="error";try{respond(exchange,502,Map.of("error",error.getClass().getSimpleName()));}catch(Exception ignored){}}
        finally {GATEWAY.requests.add(1,Attributes.builder().put("route","/orders").put("outcome",outcome).build());GATEWAY.duration.record((System.nanoTime()-start)/1e9,Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("route"),"/orders"));span.end();exchange.close();}
    }
    public static void main(String[] args) throws Exception {
        String namesrv=System.getenv().getOrDefault("NAMESRV_ADDR","127.0.0.1:9876");REDIS.ping();
        PRODUCER.setNamesrvAddr(namesrv);PRODUCER.setSendMsgTimeout(10000);PRODUCER.start();
        CONSUMER.setNamesrvAddr(namesrv);CONSUMER.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        CONSUMER.setConsumeThreadMin(1);CONSUMER.setConsumeThreadMax(1);CONSUMER.subscribe(TOPIC,"*");
        CONSUMER.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
            try {for(MessageExt message:messages)consume(message);return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;}
            catch(Exception error){error.printStackTrace();return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
        });CONSUMER.start();
        HttpServer orders=HttpServer.create(new InetSocketAddress("127.0.0.1",8081),0);orders.createContext("/orders",SystemLab::order);orders.setExecutor(Executors.newVirtualThreadPerTaskExecutor());orders.start();
        HttpServer gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",8080),0);gateway.createContext("/",SystemLab::gateway);gateway.setExecutor(Executors.newVirtualThreadPerTaskExecutor());gateway.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{gateway.stop(0);orders.stop(0);CONSUMER.shutdown();PRODUCER.shutdown();GATEWAY.close();ORDERS.close();WORKER.close();REDIS.close();}));
        System.out.println("READY http://127.0.0.1:8080");
    }
}
