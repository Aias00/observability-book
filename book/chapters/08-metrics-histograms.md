# 第 8 章：用指标描述群体，而不丢掉分母

一次正常请求不能说明整段时间的服务质量，一次很慢的请求也不能说明所有用户都受影响。指标把大量执行压缩成可以比较的序列。压缩意味着必须选择保留哪些维度，以及愿意丢弃哪些细节。

## 8.1 计数器表达累计事件

`lab.requests` 在网关和订单服务结束请求时增加，标签只有稳定路由与 `ok/error` 结果；资源带服务名与版本。它不是一个“当前并发请求数”，不能在请求结束时减回去。Prometheus 导出的序列名是 `lab_requests_total`，累计值在进程重启时可能回到较小数字，速率计算需要识别这种重置。

计算入口错误比例时，先聚合错误速率，再除以全部速率：

```promql
sum(rate(lab_requests_total{service_name="book-gateway",outcome="error"}[1m]))
/
sum(rate(lab_requests_total{service_name="book-gateway"}[1m]))
```

不要先求每个实例的百分比再简单平均。一个实例处理一万次请求，另一个只处理一次，它们不能拥有相同权重。也不要混入健康检查来稀释业务错误率。本实验 `/health` 与 `/lab/*` 不进入业务计数；实验规则因此有明确分母。

## 8.2 直方图保留分布的近似

请求耗时记录为秒，并使用显式桶边界。Prometheus 中 `_bucket` 是累积桶：`le="0.5"` 表示小于等于半秒的观察次数，不表示只处于相邻两个边界之间的次数。要观察一段时间的分布，通常先对桶计数求 rate，再按 `le` 聚合。

```promql
histogram_quantile(0.95,
  sum by (le) (
    rate(lab_http_duration_seconds_bucket{
      service_name="book-gateway"}[1m])
  )
)
```

这个 p95 来自桶内插值，不是把每条原始请求重新排序。桶过宽会影响解释精度；低流量时还要关注样本量和窗口。实验中的一次 350 毫秒注入可以帮助你检查桶分布是否变化，但不能把某次计算出来的 p95 当作可复用的性能基准。

## 8.3 指标怎样指向某一次执行

单靠 `service_name` 与 `route` 无法从一条聚合序列找到唯一 trace。为此，直方图可以携带 exemplar，保留少量观察值与当时的 trace/span 上下文。本项目记录耗时时显式传入 span Context，因为 try-with-resources 关闭 Scope 后，线程上的“当前 span”已经恢复，不能指望 finally 中自然取得正确上下文。

Collector 的 Prometheus exporter 开启 OpenMetrics，Prometheus 启用 exemplar storage。验收查询 `/api/v1/query_exemplars`，确认 exemplar 里的 trace ID 能与本次应用事件关联。它不是为每一个观测值都保存 trace，也不是给指标增加 trace_id 标签；两者对序列数量的影响完全不同。

指标、exemplar、trace 的保存时间可能不同。点击一个 exemplar 后查不到 trace，既可能是采样、过期，也可能是导出失败。查询界面需要允许这种情况，并保留原始指标作为群体信号。

**练习。** 两个实例分别处理 1000 次和 10 次请求，错误率分别是 1% 和 50%。计算整个服务的错误比例，并解释为什么“平均错误率 25.5%”误导了判断。再说明将 trace ID 作为指标标签会带来什么后果。

查询与 exemplar 接口见 [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/)。
