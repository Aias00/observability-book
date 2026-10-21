# 第 4 章：让一次 HTTP 调用跨过两端仍保持身份

网关和订单服务各自生成一个 trace，画面上看起来也都有请求与耗时。问题在于，维护者无法证明这两个请求就是同一次调用。时间接近不是因果证据，尤其在高并发、重试和时钟偏差存在时。

## 4.1 上下文不是业务正文的一部分

W3C Trace Context 用 `traceparent` 传播 trace ID、父 span ID 和标志位。业务 JSON 仍只表达创建订单的意图。将传播身份放在独立协议字段里，既方便跨语言接入，也避免每个业务消息都自行发明一套上下文格式。

本实验网关在入口使用 propagator 的 `extract`，以收到的上下文创建 SERVER span；向订单服务调用时，先创建 CLIENT span，使其成为当前上下文，再向 HttpRequest.Builder 注入头。订单服务提取这个头后创建自己的 SERVER span。因此服务端父 span 指向调用方的 client span，而不是随便指向某个仍然存在的入口 span。

这个顺序很重要。如果先 inject、再创建 client span，订单服务可能直接成为网关入口的子节点，真正的远程调用区间反而与它成为兄弟关系。整个 trace ID 仍相同，单靠“ID 没丢”检查就发现不了。我们的验收保留 span ID 与父 ID，要求在必要边界检查具体父子关系。

## 4.2 用真实请求验证传播

单独运行服务后，可以提交一个合成订单：

```sh
curl -sS http://127.0.0.1:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"id":"http-example"}'
```

响应的 `trace_id` 是后续查询入口。不要把书中某次运行的 ID 写进脚本，重新运行会产生新身份。使用这个 ID 查询 Tempo，应该找到网关 server、网关 client 与订单 server。HTTP 响应还带 `X-Trace-Id`，便于人在排查时取得关联标识；它不是权限凭证，也不能替代服务端审计。

网关计时包含发起和等待上游的时间，订单服务计时从自身收到请求开始。两者之间的差值可能包括连接、网络、调度与未覆盖区间，不能直接命名为“网络耗时”。需要更精细的证据时，才继续引入连接池和网络层仪表化。

## 4.3 传播边界也是信任边界

外部调用方可以提供自己的 traceparent。它可能格式错误，也可能带着不适合内部采样策略的标志位。本书采用标准提取器处理格式，并用 parent-based sampler 演示继承；没有实现面向不可信租户的身份隔离和采样配额。生产入口需要明确何时接受外部上下文、何时新建内部 trace，并通过 link 保存必要关联。

不要把可识别用户的信息放进 baggage 只是为了方便传递。baggage 会继续向下游传播，传播范围可能超出最初预期。订单标识在实验里作为明确的业务字段传递，只有需要的遥测位置才把它记录为属性。

**练习。** 你看到网关和订单服务的 trace ID 相同，却发现订单 server 的父 ID 等于网关 server 的 span ID。给出一段可能造成该结果的错误注入顺序，并说明为什么普通业务测试仍可能全部成功。

协议格式见 [W3C Trace Context](https://www.w3.org/TR/trace-context/)，提取与注入机制见 [OpenTelemetry 上下文传播](https://opentelemetry.io/docs/concepts/context-propagation/)。
