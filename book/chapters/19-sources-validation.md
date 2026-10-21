# 附录 C：版本、资料与验证范围

## C.1 固定学习基线

| 组件 | 固定版本 | 用途 |
| --- | --- | --- |
| Java | 21 | HTTP 服务、客户端与应用运行 |
| OpenTelemetry Java SDK | 1.55.0 | 实际 traces、metrics、logs 与 OTLP HTTP 导出 |
| Jedis | 5.2.0 | Redis 命令 |
| RocketMQ 客户端 / Broker | 5.3.2 | 经典 Remoting 消息链路 |
| Redis | 7.2.5-alpine | 专用订单键与处理标记 |
| Collector Contrib | 0.111.0 | OTLP 接收与三信号管线 |
| Tempo | 2.6.1 | 真实 trace 存储与 HTTP 查询 |
| Prometheus | 2.55.1 | 指标、exemplar 与规则状态 |

镜像标签与 registry manifest 摘要保存在仓库 `book/image-lock.json`，Compose 使用摘要约束实际镜像。这是已获取并用于本书实验的版本组合，不是对当前最新版本、安全状态或生产兼容性的推荐。源码依赖由 Maven 固定版本，下载与编译也属于 CI 的真实检查。

## C.2 一手资料

以下资料于 2026-10-21 获取。页面可能继续更新，仓库 sources.json 保存获取状态、长度与 SHA-256；书中的配置和验证范围以固定代码与实际运行返回为准。正文是原创教学解释，没有把外部文档逐页转载成书。

- [otel-java](https://opentelemetry.io/docs/languages/java/instrumentation/)。获取状态：成功；来源指纹 `70c8aea0080b8bac8f493c2820e41f944d5da36678cc69c90e1024fef1ddbf58`。
- [otel-context](https://opentelemetry.io/docs/concepts/context-propagation/)。获取状态：成功；来源指纹 `6f93c34180bb37708bac35a7368e64dcc76210359072f0525513fff9728c5f6b`。
- [collector](https://opentelemetry.io/docs/collector/configuration/)。获取状态：成功；来源指纹 `17f2f71cb64b97948672e2ba70ba49c6ea18278c18494c6bb1b7163af5137003`。
- [tempo-api](https://grafana.com/docs/tempo/latest/api_docs/)。获取状态：成功；来源指纹 `8026b95400ea1418e3be8e5471fcd563b38437b29a14e54f535db268aa141abe`。
- [prometheus-api](https://prometheus.io/docs/prometheus/latest/querying/api/)。获取状态：成功；来源指纹 `cd3dee4480931bbf8feb034f480a2955bca6429b73ac81ea8f02f673a2758c43`。
- [rocketmq-docker](https://rocketmq.apache.org/docs/quickStart/02quickstartWithDocker/)。获取状态：成功；来源指纹 `df4d3d794a0b84120096ec68d8eec8d343e6bd79fabe1acf59d69bab32484cb3`。
- [jedis](https://redis.io/docs/latest/develop/clients/jedis/)。获取状态：成功；来源指纹 `b30721e77facc94ed235e0e6c8df3938fd0cba25604065903ee0ba333b5388db`。
- [otel-pom](https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-sdk/1.55.0/opentelemetry-sdk-1.55.0.pom)。获取状态：成功；来源指纹 `91509d39ac6c48c8cc540b1c75d0f5928b961dba2a763704174bb10d77659ce6`。
- [jedis-pom](https://repo.maven.apache.org/maven2/redis/clients/jedis/5.2.0/jedis-5.2.0.pom)。获取状态：成功；来源指纹 `7e621dd391b4d282ee0480c2eea2f520963ed77fba94fe802817bb6b1b8f1043`。
- [rocketmq-pom](https://repo.maven.apache.org/maven2/org/apache/rocketmq/rocketmq-client/5.3.2/rocketmq-client-5.3.2.pom)。获取状态：成功；来源指纹 `d793eae37b76c193d6d89479fe73cfa42eca407ba42cc2471aca12c10c08931b`。
- [trace-context](https://www.w3.org/TR/trace-context/)。获取状态：成功；来源指纹 `9e7228d2a91c5aa4bef6e7f610366a2000274ee51699053e10c8ac3f0b8965be`。
- [slo](https://sre.google/workbook/alerting-on-slos/)。获取状态：成功；来源指纹 `340f148f65a7a92596c65f0a44828cbc1bcf165291cccf2c33a2b57673dd30b3`。
- [metric-naming](https://prometheus.io/docs/practices/naming/)。获取状态：成功；来源指纹 `87edfb89793d20f2dee29f0b4e048479f13078d0ba73d7e9d68174a5c92d08bd`。
- [metric-model](https://opentelemetry.io/docs/specs/otel/metrics/data-model/)。获取状态：成功；来源指纹 `5d87d113d13ef2abcb30d82df727acb1279ec804d26ea0d295041b6deb17a4cc`。
- [sampling](https://opentelemetry.io/docs/concepts/sampling/)。获取状态：成功；来源指纹 `2b3b44e6d9be5be7ee981bfb01df24035008da697aa6eea24b2c9bdd5ed6ec86`。
- [log-model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)。获取状态：成功；来源指纹 `50a5347b8f30bea3aad9d0b4bd0ad0190028e9084fc69cd1996b8ff963d5dec7`。

## C.3 可复核的运行证据

最早通过的真实链路基线为 [Actions 39570406550](https://github.com/Aias00/observability-book/actions/runs/39570406550)，对应提交 `e831a81bd33d728a173a8ff1924d9ef853cd6526`。该次正常场景取得七个 span，客户端注入等待观察值约 351 毫秒，消费者等待约 451 毫秒，累计入口请求 16 次，告警为 firing，采样零阶段 Tempo 返回 404。这些是该次 CI 的实际结果，不是跨环境性能基准。

增强实测 [Actions 39573012520](https://github.com/Aias00/observability-book/actions/runs/39573012520) 已通过 exemplar 与 Collector 停机恢复验收；当前发布对应的提交、运行地址、EPUB 哈希、场景结果和原始证据包以[下载区验证记录](https://aias00.github.io/observability-book/downloads/validation.json)为准。发布管线要求集成证据提交与出版提交一致，避免拿旧实验为新实现背书。

第一轮未通过的[真实运行 39569432908](https://github.com/Aias00/observability-book/actions/runs/39569432908)保留了 mqadmin 退出码误判的问题。它没有被当成成功证据，修正与新的验收分别保留在提交历史中。

## C.4 范围与权利

已执行的检查围绕合成订单、专用 Redis 键、单节点 RocketMQ 和一个 JVM 中的三个逻辑服务。没有声称测试生产高可用、真实客户数据、独立主机网络、尾部采样、Loki 查询或通知发送。告警恢复与更长统计窗口作为后续练习，不能从短实验自动推导出月度 SLO。

原创正文、封面和图示采用 CC BY 4.0，署名 Aias00；原创代码采用 MIT。OpenTelemetry、Redis、Apache RocketMQ、Grafana Tempo、Prometheus 及其文档保留各自原许可与权利。本书为独立教学资料，不代表这些项目审核、背书或官方出版。仓库 LICENSE 与 LICENSE-CODE 给出具体授权范围。
