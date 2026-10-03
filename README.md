# privacy-release-gateway-java

本地化的数据发布隐私网关（Spring Boot 3.4 / Java 21）：按数据集+维度维护隐私预算，
版本化执行脱敏/最小化策略，对不同入口输出一致、可复现、可解释的加噪聚合结论，
并将所有异常归一化为稳定错误类型。支持批量发布：一次请求携带多个分组条件，
逐分组独立出结论（部分成功互不影响），预算按键对账，瞬时失败可按原请求标识重试。

- 设计、错误分类、批量语义与验证方法见 [`docs/PRIVACY_GATEWAY.md`](docs/PRIVACY_GATEWAY.md)
- 构建与测试：`mvn test`
- 启动：`mvn spring-boot:run`，示例请求见文档第 6 节
