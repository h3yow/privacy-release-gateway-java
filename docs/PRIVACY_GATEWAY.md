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
- **只有确定性结论入缓存**：成功（`ALLOWED/DEGRADED`）与确定性业务拒绝
  （越权、分组过小、预算不足等）可复现，重发命中缓存；`internal_processing_error`
  视为瞬时故障**不入缓存**——底层恢复后同一 `requestId` 原样重发即可拿到新结论，
  不必换标识（见 §5.3）。

请求处理流水线（所有拒绝点都位于预算扣减之前）：

```
报文校验 → 数据集存在性 → 策略版本解析 → 维度过滤
        → 策略求值（越权 / 冲突 / 字段缺失降级）→ 分组阈值
        → 确定性噪声（纯函数，无副作用）→ 聚合（纯计算）
        → 预算原子扣减（唯一副作用点）→ 加噪结果
```

噪声与聚合都在扣减**之前**完成是刻意为之：两者都是无副作用的纯计算，
底层数据问题（如指标值无法解析）会在扣减前暴露，因此**任何失败 / 拒绝的净扣减
严格为零**；扣减之后只剩加噪与舍入，不可能再失败，
「扣减与结果生成处于同一可解释状态视图」。

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
- 被拒绝的请求不进入扣减；确定性业务拒绝（优先级 10–80）的快照入幂等缓存，
  `internal_processing_error`（90）不入缓存，可随底层恢复重试出新结论。

## 5. 批量发布（一次请求多个分组）

`POST /api/release/batch` 接受 `{requestId, dataset, groups:[{groupId, dimensions}],
metric, aggregation, role, policyVersion}`：数据集 / 指标 / 聚合 / 角色 / 策略版本
在批内共享，`groups` 携带多个分组条件。每个分组走与单发**完全相同**的判定路径，
结论互不依赖。

### 5.1 部分成功语义

- 只要批量级字段合法，响应恒为 HTTP 200，逐分组结论在 `results` 中
  （顺序与请求一致）；`batchError` 仅当整批报文不合法（如缺 `requestId`、
  `groups` 为空）时出现，此时 HTTP 400 且不处理任何分组。
- 每个分组结论（`GroupOutcome`）与单发响应字段一一对应：
  `status(ALLOWED/DEGRADED/REJECTED)`、加噪值 `value`、`groupSize`、
  `budgetBefore/Delta/Remaining`、`notices`（降级说明）、`errorCode/errorMessage`、
  `decisionBasis`。
- 某个分组被拒绝或内部失败**不影响其它分组**：能出结论的分组照常出结论，
  响应头部的 `allowedGroups / degradedGroups / rejectedGroups` 给出计数。

### 5.2 预算对账

- 只有真正出了结果的分组（`ALLOWED/DEGRADED`）才按规则扣减；
  被拒绝 / 失败的分组 `budgetDelta` 恒为 0，一分预算都不占。
- 批内多个分组维度相同则**共用同一预算键**，扣减在同一账户锁上串行发生，
  响应中可见链式衔接（后一分组的 `budgetBefore` == 前一分组的 `budgetRemaining`）；
  维度不同则各用各的账户。并发下同一预算键不会被扣超（账户内置锁保证）。
- 响应的 `budgetUsage` 按预算键汇总本批净扣减：`totalDelta` 等于该键下本次调用
  真正扣减之和，`remaining` 为本批处理后的余额。对账不变量：
  **Σ(各组 budgetDelta) == Σ(budgetUsage.totalDelta) == 本次调用对账本的实际影响**，
  且与事后 `GET /api/budgets?dataset=…&k=v` 查到的账户状态一致。
- 命中幂等缓存的重放分组（`deduplicated=true`）本次没有产生新扣减，
  不计入 `budgetUsage`，保证上述等式对每一次调用都严格成立。

### 5.3 失败与重试语义

- 分组因底层数据 / 内部处理失败时，对外只有稳定类型 `internal_processing_error`
  与安全文案，不夹带堆栈、类名等内部细节；该分组净扣减严格为零
  （聚合等纯计算已移到扣减之前，见 §1 流水线）。
- **瞬时失败不入幂等缓存**：底层恢复后，同一 `requestId` 原样重发，
  失败分组会重新判定并给出新结论；同批已成功 / 已确定拒绝的分组命中各自缓存，
  不会重复扣预算。幂等粒度 = `requestId + 分组标识`
  （`groupId`，缺省时为组内下标），因此重试时请求体应保持原样。
- 确定性业务拒绝（没权限、分组太小、预算不够等）保持幂等可复现：
  同一 `requestId` 重发返回缓存的同一拒绝结论，不会再次扣预算。
- 以上语义对单发路径同样生效：`internal_processing_error` 不再被缓存，
  单发请求在底层恢复后用同一 `requestId` 重发同样能拿到新结论。

### 5.4 与旧用法的兼容范围

- `POST /api/release` 单发请求的报文、响应字段、状态码与判定行为完全不变；
  唯一的行为修正是 `internal_processing_error` 不再被幂等缓存固化
  （旧行为下同一 `requestId` 会永远返回失败，这正是本次要修掉的问题）。
- 批量端点是新增能力，不改变单发路径；批量中每个分组的判定规则、
  错误分类、预算单价与单发完全一致。

## 6. 接口

- `POST /api/release`：请求体
  `{requestId, dataset, dimensions{}, metric, aggregation(COUNT|SUM|AVG),
  role, policyVersion}`；成功 200（`ALLOWED/DEGRADED`），拒绝 422（`REJECTED`）。
- `POST /api/release/batch`：请求体
  `{requestId, dataset, groups:[{groupId, dimensions{}}], metric, aggregation,
  role, policyVersion}`；逐分组结论 + 预算对账单，恒 200（整批报文不合法时 400），
  语义见 §5。
- `GET /api/budgets?dataset=sales&region=us`：账户总额 / 余额 / 已耗。
- `GET /api/policies`、`GET /api/policies/{version}`：已注册版本与参数。

示例：

```bash
curl -s -XPOST localhost:8080/api/release -H 'Content-Type: application/json' -d '{
  "requestId":"demo-1","dataset":"sales","dimensions":{"region":"us"},
  "metric":"amount","aggregation":"SUM","role":"analyst","policyVersion":"policy-sales-v1"}'

curl -s -XPOST localhost:8080/api/release/batch -H 'Content-Type: application/json' -d '{
  "requestId":"demo-batch-1","dataset":"sales",
  "groups":[{"groupId":"us","dimensions":{"region":"us"}},
            {"groupId":"eu","dimensions":{"region":"eu"}}],
  "metric":"amount","aggregation":"SUM","role":"analyst","policyVersion":"policy-sales-v1"}'
```

## 7. 验证方法

```bash
mvn test                       # 全部 26 个用例
mvn test -Dtest=BatchReleaseTest
```

| 测试类 | 覆盖边界 |
|--------|----------|
| `BatchReleaseTest` | 批量部分成功部分拒绝互不影响；共享/独立预算键对账（响应数字 == 账本状态）；12 线程并发抢同一预算键不超扣；失败分组零扣减且错误类型稳定无内部细节；底层恢复后同一 requestId（批量与单发）重试拿到新结论且已成功分组不重复扣；确定性拒绝幂等零扣减 |
| `BudgetConcurrencyTest` | 50 线程抢总额 1.0/单价 0.1：恰好 10 成功、40 拒绝，扣减合计严格等于总额 |
| `BudgetExhaustionIntegrationTest` | 串行第 4 次可区分拒绝且不再扣；20 个不同 requestId 并发仅 3 个成功 |
| `IdempotencyTest` | 同 requestId 重复请求返回相同结论、预算只扣一次；拒绝结论同样幂等且零扣减 |
| `PolicyVersionTest` | 同版本同输入逐位复现；v1/v2 噪声尺度（1.0 vs 2.0）与投影不同；未知版本拒绝 |
| `RejectionClassificationTest` | 7 种拒绝原因可区分；越权(40)+冲突(50) 并存时确定性归为越权；缺失字段降级带 notice |
| `DeterministicNoiseTest` | 噪声纯函数复现、版本/维度敏感性、scale=1/epsilon |
| `ApiIntegrationTest` | HTTP 成功路径、坏 JSON 归一化、越权区分、预算端点、批量部分成功与整批 malformed |

测试与运行日志对每次判定打印 **请求标识、预算变化（before→remaining(-delta)）与
判定依据**；批量请求中每个分组的日志行带有 `requestId/分组标识` 前缀，
批量末尾另有一行按预算键的对账汇总，例如：

```
requestId=exh-seq-2 判定=ALLOWED 预算变化 key=exh-…#region=us 2.0000->1.0000(-1.0000)
  依据=policyVersion=… | groupSize=3>=3 | epsilon=1.0 | budget 2.0000-1.0000=1.0000 | noiseScale=1.0000
requestId=r-prio 判定=REJECTED 预算变化=0 依据=access_denied detail=角色无权访问字段: region
requestId=batch-recon-…/g1 判定=ALLOWED 预算变化 key=…#region=us 3.0000->2.0000(-1.0000) 依据=…
requestId=batch-recon-… 批量汇总 总数=3 正常=3 降级=0 拒绝=0
  预算对账=…#region=us 净扣减=2.0000 剩余=1.0000, …#region=eu 净扣减=1.0000 剩余=2.0000
```
