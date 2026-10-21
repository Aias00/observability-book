# 附录 A：运行命令、接口与证据字段

## A.1 从一台新环境开始

需要 JDK 21、Maven、Docker Engine 与 Compose 插件。macOS 的 Java 可能安装在 Homebrew 或单独 JDK 目录，应先实际运行 `java -version` 与 `mvn -version`，确认 Maven 使用的 Java 与预期一致。不能因为 `/usr/bin/java` 文件存在，就假定运行时可用。

```sh
java -version
mvn -version
docker version
docker compose version
mvn -f lab/pom.xml --batch-mode --no-transfer-progress package
bash lab/run_ci.sh
```

脚本是一次完整验收，退出时会关闭它创建的栈。若想手工查询，先独立执行 `docker compose -f lab/compose.yaml up -d`，按照 run_ci.sh 的就绪步骤创建并查询主题，再运行 jar。手工运行后，由操作者确认工作目录与 project，再清理同一实验栈。不要把示例中的 down -v 换成针对已有共享服务的清理命令。

## A.2 业务与验收接口

| 接口 | 含义 | 不代表什么 |
| --- | --- | --- |
| POST /orders | 写入订单、发布消息，正常返回 202 | 消费者已经完成 |
| GET /orders/{id} | 通过真实 Redis 查询订单是否存在 | 与创建请求共享同一 trace |
| GET /health | Java 应用已完成启动 | 遥测后端已有本次 trace |
| GET /lab/events | 最近合成事件，供验收比对 | 持久日志或审计数据库 |
| GET /lab/flush | 缩短 SDK 导出等待 | 下游一定完整保存所有信号 |

POST 正文字段为 id、redis_delay_ms、worker_delay_ms、propagate、fail。id 限制为 1—40 位小写字母、数字、连字符；延迟限制为 0—1500 毫秒。所有参数仅用于独立实验，不应直接暴露为真实服务的故障接口。

## A.3 查询入口

Tempo 使用 `http://127.0.0.1:3200/api/traces/<id>`。Prometheus 使用 `http://127.0.0.1:9095/api/v1/query`、`/api/v1/alerts` 与 `/api/v1/query_exemplars`。Collector 健康入口为 13133，Prometheus exporter 为 8889，宿主 Java 向 4318 导出 OTLP。RocketMQ NameServer 为 9876，Broker 经典客户端为 10911。

PromQL 作为 URL 参数时应编码，避免 `{}`、引号或加号被 shell 与 HTTP 客户端改变。检查器使用标准 URL 编码；手工查询可以使用 curl 的 `--get --data-urlencode 'query=...'`。

## A.4 证据包怎样阅读

先看 commit.txt、java-version.txt、images.txt，确定执行基线。再看 results.json，取得各场景 trace ID 与检查结论。`trace-normal.json` 等文件保留 Tempo 原始数据；events.json、application.log、collector.log 用于日志关联；metrics-requests.json、metrics-p95.json、exemplars.json 与 alerts.json 是实际后端返回。

sampling-zero.json 保存未采样时的消费者事件、指标和 404；collector-outage.json 与 collector-recovered.json 区分业务可用性、恢复后的新 trace 和旧 trace 的查询结果。任何一个文件缺失，都不能用另一个阶段的成功代替它。

完整管线发布时，下载区会附带证据 ZIP、validation.json 与 EPUBCheck 日志。机器可读验证记录中的 EPUB SHA-256 对应本次构建，避免校验日志与下载文件错配。
