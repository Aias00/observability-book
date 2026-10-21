# 第 3 章：让 span 描述真实工作区间

span 的价值不在数量，而在它回答了什么问题。给每个函数套上计时器，可能得到上千个区间，却仍然看不见等待资源、调用远端和业务提交之间的关系。仪表化首先需要选择有意义的边界。

## 3.1 父子关系与时间范围

入口的 server span 描述网关处理一次请求。它内部有一个 client span，描述网关向订单服务发起的 HTTP 调用；订单服务再建立自己的 server span。客户端与服务端区间有重叠是正常的，它们从不同观察点描述同一次远程操作，不能直接相加计算总耗时。

Redis 操作和 RocketMQ 发布分别是订单服务内部的 client、producer span。消费者使用 consumer span，后面还包含写入处理结果的 Redis client span。这样选择边界，是为了区分同步响应、发布确认和异步业务完成。发布成功不需要等待消费完成，消费者 span 因而可能在入口结束后继续运行。

创建 span 不会自动让它成为当前上下文。Java 代码使用显式 Scope：

```java
Span span = telemetry.tracer.spanBuilder("redis SET order")
    .setSpanKind(SpanKind.CLIENT).startSpan();
try (Scope ignored = span.makeCurrent()) {
    redis.setex(key, 3600, value);
} finally {
    span.end();
}
```

`makeCurrent()` 影响当前执行范围里随后创建的子 span、日志上下文和传播头。关闭 Scope 恢复先前上下文；`end()` 结束计时。这两个动作职责不同，只做其中一个都会留下问题。漏掉 Scope 关闭可能让后续工作错误继承身份，漏掉 end 则可能使 span 长期留在未完成状态。

## 3.2 记录错误时避免改变原问题

异常被记录到 span，并不自动意味着业务向用户返回失败；有些异常会被恢复。反过来，一个受控返回 503 的分支也未必抛异常。本实验显式设置 ERROR 状态，同时保留 HTTP 结果，避免把“没有异常堆栈”误解为成功。

仪表化本身也可能失败。Exporter 不应把网络导出放到业务关键路径同步等待；本实验使用 BatchSpanProcessor。它在后台批量导出，但队列和重试都有界，不能承诺采集故障期间的数据永远不会丢失。`/lab/flush` 是验收工具，用来缩短等待已结束数据导出的时间，不是生产接口设计。

## 3.3 名字稳定，细节放在属性

`redis SET order` 是稳定操作名，具体订单号放在 `order.id` 属性。若将每个订单号都拼进 span 名称，后续按操作聚合会变得困难。类似地，HTTP 使用 `/orders` 路由模板，不把完整 URL 当成操作类别。

Trace 属性可以高选择性地包含业务关联标识，但这不意味着可以无边界记录请求正文。实验仅记录合成订单标识，不记录真实客户信息。一个有用的检查是：如果这条属性被复制到日志、告警或共享截图里，是否仍符合团队的数据规则？

运行后，可以在 Tempo 返回中检查 `startTimeUnixNano`、`endTimeUnixNano`、`spanId` 和 `parentSpanId`。验收程序把它们转换成便于比较的记录，但仍保留原始 JSON，避免只剩作者加工过的结论。

**练习。** 如果一段异步任务在创建时继承了父上下文，但父 span 已结束，它就一定是错误的父子关系吗？说明时间重叠与因果关系分别表达什么，并给出一个应考虑使用 link 的场景。

SDK、Tracer、Span 与 Scope 的具体用法见 [OpenTelemetry Java 仪表化文档](https://opentelemetry.io/docs/languages/java/instrumentation/)。
