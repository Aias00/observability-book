# 全链路可观测性：从一次慢请求到故障定位

中文工程读本正在以真实可执行系统为基线编写。主线为 Java HTTP 网关、Java 订单服务、Redis 与 RocketMQ 消费者；OpenTelemetry Java SDK 向 Collector 导出 traces、metrics、logs，分别以 Tempo 查询、Prometheus 查询和关联日志验证。

当前先验证实验，再依据实际证据完成正文。在线站点、EPUB 与完整书稿发布后会补充正式入口。

实验只访问专用 `otelbook:` Redis 键，使用独立 Docker Compose project。运行需要 JDK 21、Maven、Docker Compose；不要复用生产端口或数据目录。

```sh
mvn -f lab/pom.xml package
bash lab/run_ci.sh
```

运行记录由 CI 保存为 `observability-evidence` artifact。模拟等待明确放在 Redis 客户端 span 内，不能据此声称 Redis 服务本身变慢；本书不把演示告警状态当成通知系统已经交付。
