# 第 9 章：Collector 是数据管线，不是万能缓冲区

把 SDK endpoint 改成 Collector 后，很多团队会认为采集可靠性已经被统一解决。实际上，Collector 只是把接收、处理和导出组织到一个独立进程里；队列、内存、重试、磁盘和下游可用性仍然存在边界。

## 9.1 从接收协议走到导出合同

本书 Java SDK 使用 OTLP HTTP，分别向 `/v1/traces`、`/v1/metrics`、`/v1/logs` 导出。Collector 的一个 OTLP receiver 可以接收三个信号，但 service pipelines 要分别声明它们的处理与去向。仅在配置里定义 exporter，而没有在 pipeline 里引用，并不会让数据自动流向它。

Trace 管线导出到 Tempo 的 OTLP gRPC 地址 `tempo:4317`；metrics 管线暴露 Prometheus exporter，由 Prometheus 主动抓取；logs 管线写 debug exporter。它们没有共享同一种交付确认语义：SDK 的 HTTP 导出成功，不意味着稍后的 Prometheus 抓取已经完成，也不意味着日志已经进入一个可长期查询的数据库。

按这个顺序排查很实用：业务是否记录了信号，SDK 是否有可导出数据，Collector receiver 是否接收，processor 是否拒绝，exporter 是否成功，最后才是后台是否按预期查询。每一段都有可能让“我已经接入 OTel”与“我可以解释这次请求”之间出现断层。

## 9.2 batch 与 memory_limiter 的取舍

实验把 memory_limiter 放在 batch 前面，限制进程接收压力，再将小批次攒成更合适的导出单元。batch 的 timeout 是延迟与效率之间的取舍；设得更小可以缩短可见等待，却增加请求开销。它不能被当作端到端可见时间的严格上限，因为下游、队列和重试也需要时间。

memory_limiter 在压力下可能拒绝数据，让上游有机会退避。它不是操作系统级的硬内存限制，也不保证整个容器永不 OOM。SDK 和 Collector 都可能拥有自己的缓冲，评估容量时需要把多个位置的排队同时考虑，而不是只计算一个配置项。

本书没有配置持久化发送队列。Collector 被停止或机器断电时，不应承诺未导出的所有数据可以恢复。生产若需要更强的交付保证，需要明确支持持久队列的组件、磁盘位置、容量、重放顺序及下游限流，并通过故障注入验证。

## 9.3 健康接口回答的是谁的问题

`health_check` 的 13133 可以帮助判断 Collector 进程是否能响应，但不是业务 trace 的查询接口。类似地，Prometheus 的 up=1 只说明抓取成功，不说明 Java 请求计数正在增长。最好在运行手册中把“进程可达”“管线有流量”“后端可查询”写成三项不同检查。

实验的故障阶段会停止本次 CI 专用 Collector，再提交业务请求，观察业务是否仍可完成；恢复后再提交新请求，检查新的完整 trace。对故障窗口里旧 trace 的查询结果只如实保存，不把后来恢复连接解释为历史数据绝不丢失。

**练习。** SDK 导出返回成功，但 Prometheus 看不到新的请求指标。按本章的管线写出四个检查点，分别说明应查看哪个 endpoint、配置引用或运行信号。不要把重启所有组件作为第一步。

具体组件与 pipeline 规则见 [Collector 官方配置文档](https://opentelemetry.io/docs/collector/configuration/)。
