# 全链路可观测性：从一次慢请求到故障定位

一本面向 Java 后端开发者的中文工程读本，从真实 HTTP、Redis、RocketMQ 订单链路出发，以 OpenTelemetry Java SDK / Collector、Tempo、Prometheus 和关联日志解释慢请求、异步上下文与故障。

**[在线阅读](https://aias00.github.io/observability-book/)** · **[下载 EPUB](https://aias00.github.io/observability-book/downloads/observability-book.epub)** · **[GitHub Release](https://github.com/Aias00/observability-book/releases/latest)** · **[本次验证记录](https://aias00.github.io/observability-book/downloads/validation.json)**

前言、16 章正文、3 个资料附录，另自动生成完整实验代码附录。正文覆盖证据模型、HTTP 因果、Redis 观察点、消息上下文、OTLP 日志、直方图与 exemplar、Collector、Tempo 查询、采样、基数/隐私、SLO 和故障演练。配套原创 SVG 封面与两幅系统图。

## 真实实验

三个逻辑服务在同一个 JVM 中，使用三个独立 service.name；服务之间仍经过实际 HTTP、Redis 和 RocketMQ 连接。OTel SDK 通过 OTLP HTTP 导出三种信号，Collector 将 trace 送到 Tempo、指标暴露给 Prometheus 抓取、日志送到 debug exporter。没有用内存 trace 代替后端。

```sh
java -version
mvn -version
docker version
docker compose version
mvn -f lab/pom.xml --batch-mode --no-transfer-progress package
bash lab/run_ci.sh
```

需要 JDK 21、Maven 与 Docker Compose。只使用专用 `otelbook:` Redis 键，绑定本机端口；退出时脚本清理自己创建的 Compose project。不要复用生产依赖。`lab/compose.yaml` 使用固定镜像摘要；依赖与一手资料见 `book/image-lock.json`、`book/sources.json`。

真实验收包含：HTTP/MQ 父子关系、业务成功但消息上下文断开的负例、350/450 毫秒注入、日志与 trace 关联、Prometheus histogram/exemplar、低基数标签、SLO firing、Collector 停机/新数据恢复、采样零时业务/指标继续且 Tempo 404。原始查询与日志打包为[集成证据](https://aias00.github.io/observability-book/downloads/observability-evidence.zip)，不只保留通过标记。

## 生成与部署

需要 Python 3.10+、Pandoc 3.12.1。网页版采用静态分章、原生目录、前后章导航、搜索、字号、浅色默认及共享深色偏好。关闭 JavaScript 仍可阅读与翻页。

```sh
python3 scripts/build.py
python3 scripts/verify.py
sh scripts/epubcheck.sh
python3 -m http.server 8771 --bind 127.0.0.1 --directory site
```

`site/`、`.build/` 和临时工具不提交。`scripts/verify.py` 比较原稿、网页、EPUB 的逐章完整文字；EPUBCheck 检查格式。详情及可选浏览器复测见 [构建说明](scripts/README.md)。完整发布还会将本提交的真实集成 evidence 加入下载区；GitHub Actions 先跑实际 Docker/Java 验收，再构建、EPUBCheck、320/390px 浏览器检查并部署 Pages。

## 验证边界与许可

Redis 与 RocketMQ 为单节点，Java 三角色共用 JVM；未声称生产高可用或独立主机验证。Redis 注入是客户端区间内的等待，不是 Redis 服务端延迟测试。没有部署 Grafana、Loki 或 Alertmanager；OTLP 日志到达 Collector 与 Prometheus firing 不等于日志检索、通知链或告警恢复已交付。尾部采样与生产治理作为设计讨论，未冒充实跑。

原创正文和图示 CC BY 4.0，代码 MIT，署名 Aias00；第三方项目与资料保留各自权利，详见 LICENSE、LICENSE-CODE。
