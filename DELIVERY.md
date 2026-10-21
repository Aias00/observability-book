# 交付记录：全链路可观测性

交付日期：2026-10-21。作者与署名：Aias00。

## 公开入口

- 在线阅读：https://aias00.github.io/observability-book/
- EPUB：https://aias00.github.io/observability-book/downloads/observability-book.epub
- 源码与完整书稿：https://github.com/Aias00/observability-book
- Release v1.0.0：https://github.com/Aias00/observability-book/releases/tag/v1.0.0
- 本次机器可读验证：https://aias00.github.io/observability-book/downloads/validation.json
- 真实实验查询与日志 ZIP：https://aias00.github.io/observability-book/downloads/observability-evidence.zip
- EPUBCheck 原始日志：https://aias00.github.io/observability-book/downloads/epubcheck.txt

本地已同步实际 CI 发布产物：`site/downloads/observability-book.epub`，101,453 字节；实际大小以 build-info.json 为准。

EPUB SHA-256：`f798054f24dd4cd14b5cce6a9ce075ecb6874a6d3d80a58bb4ede4065004442d`。公网 Pages EPUB、Release EPUB 与发布验证记录哈希一致。

## 内容规模

- 前言、16 章正文、3 个资料附录、1 个自动生成的完整实验代码附录，共 21 篇在线内容。
- 手工撰写讲解约 17,446 汉字，正文约 13,899 汉字；不把 Java、配置和测试代码计为原创汉字规模。
- 原创 SVG 封面、2 幅原生 SVG 系统图；中文元数据、Aias00 作者、EPUB 原生目录。
- 13 个官方文档页面与 3 个依赖 POM 的获取记录，另锁定 5 组容器镜像 manifest 摘要。
- Java 21 主线约 237 行 Java 源码，另有真实后端 Python 验收、Compose、Collector/Tempo/Prometheus 配置与运行脚本；完整代码与实验 ZIP 同源。

主题覆盖证据模型、HTTP 上下文、Redis 客户端等待、RocketMQ 异步传播、OTLP 日志、计数器与直方图、exemplar、Collector 管线、Tempo 查询、采样、高基数/隐私、SLO、故障调查、采集故障恢复和生产交付。

## 真实验证

发布提交：`1a568a4f5c0d93e52f2377d30370299b16b28475`。

完整 CI：https://github.com/Aias00/observability-book/actions/runs/39574082710

该运行的实验提交与出版提交相同；管线会拒绝给新出版提交附上旧提交的实验产物。本机完成 JDK 21 编译、EPUB 与浏览器检查；Docker/Redis/MQ/OTLP 后端集成在 GitHub Actions Ubuntu 24.04 实际运行，没有声称本机安装了这些服务。

本次实际证据：

- 正常 Java HTTP → Redis → RocketMQ → 消费者取得 7 个 span、3 个 service.name，consumer parent 正确指向 producer。
- `propagate=false` 仍完成业务，但入口 trace 只有对应的 5 个 span；消费者位于独立 trace，证明负例有效。
- 350ms 客户端注入的观测区间约 350.954ms；450ms 消费等待区间约 451.222ms。它们不是生产性能基准，也不是 Redis 服务端延迟测试。
- SDK 真实 OTLP logs 到达 Collector，与应用事件和 Tempo span ID 关联。
- Prometheus 真实请求计数、直方图、exemplar 与低基数标签检查通过；BookFastBurn 告警达到 firing。
- 停止专用 Collector 时，订单仍返回 202 且消费完成；恢复后的新请求重新取得三服务完整 trace。旧请求查询状态另存，不以 HTTP 200 宣称无损恢复。
- `SAMPLER_RATIO=0` 时，实际消费完成、指标仍可查、日志 sampled=false，对应 Tempo 查询 404。
- 原稿 / 静态分章 / EPUB 逐章全文一致；内部链接、资源、元数据、目录、封面、搜索索引和实验包验证通过。
- EPUBCheck 5.4.0：0 fatal、0 error、0 warning、0 info。

## 公网阅读验收

对实际 Pages 再执行 Playwright 验收，21 篇内容在 320px 和 390px 宽度均无页面横向溢出。默认浅色（即使系统深色）、深色切换、同域 `aias-reader-theme` 与跨标签同步、字号保留、搜索、原生目录、无 JavaScript 正文与翻页、存储拒绝降级、公开下载均通过，JavaScript 错误为零。

已目视检查桌面浅色与移动深色截图。截图位于本机系统临时目录 `observability-book-reader/`；本次浏览器输出的绝对目录为 `/var/folders/38/945f17ss29gbl8l87zl649bh0000gn/T/observability-book-reader/`。

## 实验边界

三个 Java 角色共用一个 JVM；Redis 与 RocketMQ 为单节点；未声称独立主机或生产高可用验证。Collector debug 日志不等于 Loki 检索平台；Prometheus firing 不等于通知已经发出。尾部采样、告警恢复、长期 SLO 与生产身份治理保留为设计或扩展，未伪称执行。全部公开 evidence 为专用实验合成数据。

## 首页书架条目

建议 id：`observability`；topic：`backend`；topicLabel：`OBSERVABILITY`；scale：`16 章 · 4 个附录`；level：`故障定位与实践`。

简介建议：从 Java、Redis 与 RocketMQ 真实链路出发，学习 OpenTelemetry、指标与日志关联、采样、SLO 和故障定位。

readUrl / epubUrl 使用本记录开头的 Pages 地址。未修改主站仓库，交由主代理统一纳入书架。
