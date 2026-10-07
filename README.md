# privacy-release-gateway-java

本地化的数据发布隐私网关（Spring Boot 3.4 / Java 21）：按数据集+维度维护隐私预算，
版本化执行脱敏/最小化策略，对不同入口输出一致、可复现、可解释的加噪聚合结论，
并将所有异常归一化为稳定错误类型。

支持批量发布：`POST /api/release/batch` 一次请求携带多个分组条件，逐组独立判定
（部分失败不连累其它分组），预算按出结果的分组精确扣减并可与预算端点对账；
整批合计 `totalBudgetDelta` 只统计**本趟**真正新扣的分组——原样重发时逐组结论逐字复现
但本趟零扣减、合计为 0，合计恒等于账本本趟差值；内部失败不占用幂等标识，
底层恢复后同一 requestId 重发即可拿到新结论（重算的分组才计入当趟合计）。

- 设计、错误分类、批量语义与验证方法见 [`docs/PRIVACY_GATEWAY.md`](docs/PRIVACY_GATEWAY.md)
- 构建与测试：`mvn test`
- 启动：`mvn spring-boot:run`，示例请求见文档第 5 节
