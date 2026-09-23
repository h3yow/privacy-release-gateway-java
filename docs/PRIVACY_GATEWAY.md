# 本地化数据发布隐私网关 — 设计与验证说明

## 1. 目标与一致性模型

网关为不同入口（REST `/api/release` 与程序内直连）提供**唯一判定路径**
（`PrivacyGateway.submit`），因此同一份数据在任意入口下得到一致、可解释的结论：

- 每个成功结论携带 `decisionBasis`：所用策略版本、分组规模阈值比较、epsilon、
  扣减前后预算、噪声尺度，以及降级说明。
- 每个拒绝结论携带可区分的稳定错误码（`errorCode`）、安全文案与优先级依据，
  且 `budgetDelta` 恒为 0。
- 结论按 `requestId` 幂等快照缓存：重复请求直接返回缓存副本（`deduplicated=true`），
  **不重复计算、不重复扣预算**；同一 `requestId` 在并发下按键串行化，
  双重检查缓存后才进入扣减。

请求处理流水线（所有拒绝点都位于预算扣减之前）：

```
报文校验 → 数据集存在性 → 策略版本解析 → 维度过滤
        → 策略求值（越权 / 冲突 / 字段缺失降级）→ 分组阈值
        → 确定性噪声（纯函数，无副作用）
        → 预算原子扣减（唯一副作用点）→ 聚合 → 加噪结果
```

噪声在扣减前生成是刻意为之：它是无副作用纯函数；唯一可能失败的聚合在扣减**之后**
执行，而聚合只读取求值阶段已确认存在且可解析的字段。这样
「扣减与结果生成处于同一可解释状态视图」，且任何拒绝都不消耗预算。

## 2. 隐私预算模型

- **粒度**：`BudgetKey = 数据集 + 排序后的维度键值对`（`sales#region=us`）。
  维度 Map 的迭代顺序不影响账户归属。
- **账户**（`BudgetAccount`）：`total / remaining / spent`。
  `tryConsume(delta)` 在实例内置锁上一次完成「余额检查 + 扣减」：
  - 余额不足返回 `null` 且余额不变（拒绝不耗预算）；
  - 成功则返回不可变扣减快照 `before / delta / remaining / spent`。
- **账本**（`BudgetLedger`）：`ConcurrentHashMap#computeIfAbsent` 保证每个键
  在并发首访时只创建一个账户；账户数量不会膨胀出多个余额。
  因此并发下预算不会被**超扣、丢失或重复计算**。
- **单价**：每次查询扣减当前策略版本的 `epsilon`（演示默认账户总额 3.0，
  v1 epsilon=1.0 → 同键允许 3 次；v2 epsilon=0.5 → 允许 6 次）。
- 响应同时返回 `budgetBefore / budgetDelta / budgetRemaining`，
  `GET /api/budgets?dataset=&k=v` 可查询任一账户状态。

## 3. 脱敏与最小化策略的版本化

- `MaskingPolicy` 是不可变版本（`version / datasets / 字段规则 / epsilon /
  minGroupSize / strictMissing`），注册到 `PolicyRegistry` 后永久保留：
  新版本上线不覆盖旧版本，历史结论始终可按其版本复现。
- 请求显式指定 `policyVersion` 时按指定版本求值；否则使用该数据集的当前默认版本。
- 字段转换 `FieldTransform`：`NONE / MASK / HASH / GENERALIZE / RESTRICT`。
  所有转换结果只依赖 `(policyVersion, field, rawValue)`：
  - HASH 使用 `SHA-256(version|field|value)` 的前 12 位，版本即盐；
  - GENERALIZE 将 epoch 秒向桶（默认 3600s）向下归整，非数值按长度桶归整。
- **默认拒绝**：请求涉及的任何字段（维度键 + 指标）若无授权规则或角色不在
  `allowedRoles` 中，一律 `ACCESS_DENIED`，绝不静默放行。
- **策略冲突**：同一字段在同一版本内出现不同 `transform` 时，触达即归类
  `POLICY_CONFLICT`（等价重复规则允许）。
- **字段缺失**：`strictMissing=true` → `FIELD_MISSING` 拒绝；
  否则降级——剔除指标缺失的行并在 `notices` 中明确说明，结论标记为 `DEGRADED`；
  若剔除后无可用行仍拒绝。
- **确定性加噪**（`DeterministicNoise`）：由
  `SHA-256(policyVersion|dataset|dimensionKey|metric|aggregation)` 派生两个均匀分量，
  经 Box-Muller 得到标准高斯，尺度 `scale = 1/epsilon`。无任何运行时随机源，
  所以同版本 + 同输入结论逐位可复现；版本或维度改变，噪声随之改变。

## 4. 错误分类与异常归一化

对外只有 9 种稳定类型（`ErrorCode`），按优先级数值升序归类。
`PolicyEvaluator` 先收集一次请求的全部字段级问题，再取优先级最小者抛出，
归类结果与字段顺序无关：

| 优先级 | code                         | 触发场景                                   | HTTP |
|-------:|------------------------------|--------------------------------------------|------|
| 10 | `malformed_request`          | JSON 解析失败 / requestId、dataset、metric、aggregation、role 等缺失 | 400  |
| 20 | `dataset_not_found`          | 数据集不存在                               | 422  |
| 30 | `policy_version_not_found`   | 指定版本不存在或不适用于该数据集           | 422  |
| 40 | `access_denied`              | 字段无授权规则（默认拒绝）或角色越权       | 422  |
| 50 | `policy_conflict`            | 同字段转换规则相互矛盾                     | 422  |
| 60 | `field_missing`              | 严格模式下字段缺失 / 降级后无可用行        | 422  |
| 70 | `group_too_small`            | 可用分组行数 < `minGroupSize`              | 422  |
| 80 | `budget_exhausted`           | 预算余额不足本次 epsilon 扣减              | 422  |
| 90 | `internal_processing_error`  | 解析 / 求值 / 加噪 / 聚合内部失败的统一兜底 | 500  |

- 分层边界各自把底层 `RuntimeException` 转换为 `GatewayException(ErrorCode)`；
  逃出控制器的异常由 `GlobalExceptionHandler` 兜底。
- 响应只含 `code + 安全文案`，**不包含堆栈、异常类名或原始 message**
  （内部细节仅以 WARN 日志记录异常类型）。
- 被拒绝的请求不进入扣减，幂等缓存同样缓存拒绝快照。

## 5. 接口

- `POST /api/release`：请求体
  `{requestId, dataset, dimensions{}, metric, aggregation(COUNT|SUM|AVG),
  role, policyVersion}`；成功 200（`ALLOWED/DEGRADED`），拒绝 422（`REJECTED`）。
- `GET /api/budgets?dataset=sales&region=us`：账户总额 / 余额 / 已耗。
- `GET /api/policies`、`GET /api/policies/{version}`：已注册版本与参数。

示例：

```bash
curl -s -XPOST localhost:8080/api/release -H 'Content-Type: application/json' -d '{
  "requestId":"demo-1","dataset":"sales","dimensions":{"region":"us"},
  "metric":"amount","aggregation":"SUM","role":"analyst","policyVersion":"policy-sales-v1"}'
```

## 6. 验证方法

```bash
mvn test                       # 全部 18 个用例
mvn test -Dtest=BudgetConcurrencyTest
```

| 测试类 | 覆盖边界 |
|--------|----------|
| `BudgetConcurrencyTest` | 50 线程抢总额 1.0/单价 0.1：恰好 10 成功、40 拒绝，扣减合计严格等于总额 |
| `BudgetExhaustionIntegrationTest` | 串行第 4 次可区分拒绝且不再扣；20 个不同 requestId 并发仅 3 个成功 |
| `IdempotencyTest` | 同 requestId 重复请求返回相同结论、预算只扣一次；拒绝结论同样幂等且零扣减 |
| `PolicyVersionTest` | 同版本同输入逐位复现；v1/v2 噪声尺度（1.0 vs 2.0）与投影不同；未知版本拒绝 |
| `RejectionClassificationTest` | 7 种拒绝原因可区分；越权(40)+冲突(50) 并存时确定性归为越权；缺失字段降级带 notice |
| `DeterministicNoiseTest` | 噪声纯函数复现、版本/维度敏感性、scale=1/epsilon |
| `ApiIntegrationTest` | HTTP 成功路径、坏 JSON 归一化、越权区分、预算端点 |

测试与运行日志对每次判定打印 **请求标识、预算变化（before→remaining(-delta)）与
判定依据**，例如：

```
requestId=exh-seq-2 判定=ALLOWED 预算变化 key=exh-…#region=us 2.0000->1.0000(-1.0000)
  依据=policyVersion=… | groupSize=3>=3 | epsilon=1.0 | budget 2.0000-1.0000=1.0000 | noiseScale=1.0000
requestId=r-prio 判定=REJECTED 预算变化=0 依据=access_denied detail=角色无权访问字段: region
```
