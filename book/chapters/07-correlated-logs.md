# 第 7 章：日志不是追踪的替代品，而是决定的证词

一条耗时很长的 span 可以告诉我们某个区间发生了等待，却未必说明系统当时为什么选择重试、返回失败或者继续处理。日志适合补充这些离散决定。问题在于，传统文本日志常常只有时间和一段自然语言，维护者只能在大量内容里猜测它属于哪次执行。

## 7.1 在事件发生处取得上下文

本实验的 `event()` 在当前 Scope 内读取 trace ID 与 span ID，同时记录服务名、事件名、合成订单号和 sampled 标志。日志作为 JSON 输出，便于保存和比较；同一个事件也通过 OTel Logs API 发送到 Collector，明确设置 `Context.current()`。

```json
{
  "service":"book-worker",
  "event":"worker.processed",
  "order_id":"example",
  "trace_id":"每次运行生成的32位标识",
  "span_id":"事件所在span的16位标识"
}
```

这是字段示意，不是可以拿去查询的运行输出。真实值保存在 evidence/events.json 与应用日志里。验收把 normal 场景的 worker 日志 span ID 与 Tempo 返回的 span ID 比较，并检查 Collector 日志里确实出现同一 trace ID，防止只在本地 JSON 里打印一个看似正确的字段。

## 7.2 本地日志、OTLP 日志与查询后台

系统同时保留两条路径：应用 stdout 和 OTel 日志管线。Collector 的 logs pipeline 使用 debug exporter，将接收到的遥测打印到自己的日志。它适合在实验中检查资源、上下文和内容，不是有索引、保留和访问控制能力的日志数据库。

因此本书可以声称验证了“带 trace 关联的日志由 SDK 经 OTLP 到达 Collector”，不能声称已经交付了 Loki 查询、生产日志检索或跨租户审计平台。若将来接入日志后端，还需要验证字段映射、保留规则、时间解析和权限，而不是只把 exporter 名称换掉。

应用事件列表只是验收辅助接口，不承担日志存储职责。它只保留最近 300 条合成事件，进程重启后消失；不应让生产系统依赖它完成审计。完整运行日志由 CI artifact 保存，关联字段则保留在 trace 与消息处理结果中。

## 7.3 让事件名字表达状态变化

`order.queued` 表示发布流程完成到本示例认为可接受的位置，`worker.processed` 表示消费者已经写入专用处理标记；它们不是同一个成功定义。`order.failed` 则对应受控应用失败。稳定事件名比“处理成功了”更容易查询，也能让读者检查事件是否出现在正确的 span 范围。

不要在每一行日志里复制整个请求。高体积正文会增加存储与检索成本，也扩大敏感信息传播。trace ID 可以让维护者跳到执行上下文，订单号可以帮助关联业务对象；两者都不需要把整个客户记录写出来。

采样关闭后，事件仍可记录 trace ID 和 sampled=false。此时按 ID 查不到 trace 是预期可能性；日志是否保留属于另一条策略。排查工具应当允许这种不对称，而不是把缺少 trace 一律显示为数据损坏。

**练习。** 如果 `worker.processed` 日志的 trace ID 正确、span ID 却不在 Tempo 中，列出三种原因。至少一种原因与采样或数据保留有关，至少一种与日志取得上下文的位置有关。

Java Logs API 的入口可从 [Java 仪表化说明](https://opentelemetry.io/docs/languages/java/instrumentation/)核查；日志和 trace 的关联不应仅靠时间戳猜测。
