# 本地化数据发布隐私网关（Privacy Release Gateway）

一套基于 Java 21 / Spring Boot 3.4 的本地化数据发布隐私网关。目标：让**不同入口（HTTP、直接服务调用、并发线程）对同一份数据的脱敏、聚合与预算扣减得到一致且可解释的结论**。

- 预算按「数据集 × 请求维度组合」建账，并发下不超扣、不丢失、不重复计算；
- 脱敏/最小化策略**版本化**执行，同版本同输入结论可复现，历史版本永久保留；
- 底层解析/策略/加噪的内部失败统一归一化为对外稳定错误码，多问题按确定性优先级归类；
- **任何被拒绝的请求都不消耗隐私预算**（含内部失败后的补偿退还）。

---

## 1. 快速开始

```bash
mvn compile          # 编译
mvn test             # 运行全部测试（含并发、幂等、版本切换、内部失败归一化）
mvn spring-boot:run  # 启动，默认 8080
```

发布请求示例：

```bash
curl -s -X POST http://localhost:8080/api/v1/release \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId":"req-001",
    "idempotencyKey":"idem-001",
    "dataset":"customers",
    "dimensions":["city"],
    "metrics":[{"type":"COUNT"}],
    "fields":["city"],
    "role":"analyst",
    "strategyVersion":1
  }'
```

`strategyVersion` 省略时使用当前生效版本（当前为 v2）。演示数据 `customers`（12 行：id/name/age/city/salary）在 `config/GatewayBootstrap` 中确定性播种。

### 响应字段

| 字段 | 含义 |
| --- | --- |
| `allowed` | 是否放行。拒绝时为 `false`，`rows` 为空，`rejectionCode` 给出可区分原因 |
| `degraded` | 是否发生降级（如 k-匿名下的小组抑制），降级不静默丢弃数据 |
| `strategyVersion` | 实际执行的策略版本号 |
| `rejectionCode` / `rejectionDetail` | 稳定错误码与脱敏后的说明（不含原始异常） |
| `budgetBefore` / `budgetCost` / `budgetRemaining` | 本次判定看到的预算前值、扣减单价、扣减后余额；拒绝时 cost=0 且 before==remaining |
| `decisionBasis` | 判定依据的人类可读文本：策略口径、预算变化、输出组数、抑制组数、噪声种子 |
| `rows` | 加噪后的聚合结果（分组维度已经过该版本策略脱敏） |

---

## 2. 隐私预算模型

### 2.1 账户口径

- 账户键 `BudgetKey = dataset + 排序归一化后的 dimensions`。
  维度顺序不同（`[city,age]` 与 `[age,city]`）指向**同一账户**，保证不同入口口径一致。
- 每个账户初始预算 `INITIAL_BUDGET = 3.0`（演示值，见 `GatewayBootstrap`）。
- 每次放行发布扣减的单价 = 所执行策略版本的 `epsilon`（v1=1.0，v2=1.5）。
- 预算在**策略判定通过之后、结果生成之前**原子扣减。

### 2.2 并发安全（不超扣/不丢失/不重复）

`BudgetStore` 对每个账户使用「不可变快照 + `AtomicReference` CAS 自旋」：

1. 读取当前快照（余额、初始总量、流水）；
2. 判定单价是否合法、余额是否充足——**失败不写入任何状态**；
3. 构造下一快照（新余额 + 追加一条流水），CAS 提交；冲突则基于最新快照重试。

因此 50 个并发请求、单价 1、总量 10 时：恰好 10 个成功、40 个 `BUDGET_EXHAUSTED`，流水首尾相接、成本之和严格等于初始总量（见 `BudgetConcurrencyTest`）。

### 2.3 幂等：重复/并发同键只扣一次

`GatewayService` 以 `idempotencyKey` 为键，用 `ConcurrentHashMap.computeIfAbsent` 保证同键的并发/重复请求**只计算一次、只扣一次预算**，后续请求（无论当时成功还是被拒绝）回放同一份结论，仅替换 `requestId` 用于链路追踪。无幂等键的请求不缓存、各自独立计费。

### 2.4 拒绝不耗预算

- 策略类拒绝发生在扣减之前，根本不触达预算；
- 预算类拒绝不写入；
- 扣减成功后若结果生成阶段发生内部故障，调用 `BudgetStore.refund` 原子退还，并追加一条负向流水。流水净额为 0，状态全程可解释（见 `InternalFailureNormalizationTest`）。

### 2.5 加噪模型

- 噪声为**双端几何分布**（离散拉普拉斯），对灵敏度 1 的 COUNT/SUM 聚合满足 ε-差分隐私；
- 噪声流由 `(策略版本 noiseSeed, 确定性上下文键)` 经字符串混合哈希确定。上下文键只含
  版本、数据集、维度、脱敏后分组键、指标名，**不含 requestId/时间**；
- 因此同版本同输入（含重复请求）噪声逐位一致；策略升级换新种子后，用旧版本号仍可取旧种子复现历史结论；加噪结果做非负截断。

---

## 3. 脱敏策略版本

策略以不可变的 `PolicyDefinition` 注册到 `PolicyRegistry`：一经注册不可覆盖，所有历史版本永久保留。每个版本包含：版本号、噪声种子 `noiseSeed`、预算单价 `epsilon`、k-匿名下限 `minGroupSize`、逐字段规则 `FieldPolicy`（允许角色、变换类型、是否允许度量/维度）。

| 字段 | v1（宽松） | v2（收紧，当前） |
| --- | --- | --- |
| id | HASH（analyst/admin） | HASH（analyst/admin） |
| name | MASK | **SUPPRESS**（请求明细即 POLICY_CONFLICT） |
| age | NONE 原值 | **GENERALIZE 10 岁桶** |
| city | GENERALIZE | GENERALIZE |
| salary | NONE（仅 admin，可 SUM） | NONE（仅 admin，可 SUM） |
| minGroupSize | 1 | **2（单元素组抑制并标记 degraded）** |
| epsilon / noiseSeed | 1.0 / 1001 | 1.5 / 2002 |

关键执行语义：

- **脱敏先于聚合**：维度值先按版本策略变换（如年龄先分桶），再分组、再做 k-匿名判定与加噪，使隐私保护作用于最终发布口径；
- **版本可复现**：显式传 `strategyVersion=1` 永远走 v1 口径与种子；省略则取当前版本；
- 小组抑制属于**降级**（`degraded=true` 且 `decisionBasis` 记录抑制组数），不是静默放行也不是整体拒绝；
- 策略未覆盖数据集字段、未知版本号、重复字段、对被抑制字段请求明细等，一律 `POLICY_CONFLICT`，不静默放行。

字段变换类型（`FieldTransform`）：`NONE` / `MASK`（保留首字符）/ `GENERALIZE`（数值 10 分桶、地址截断）/ `SUPPRESS` / `HASH`（FNV-1a 稳定伪名化）。

---

## 4. 错误分类（对外稳定码）

所有内部异常都被归一化为下列稳定码之一，响应中**绝不包含**原始异常类型、消息或堆栈（原始信息仅写入服务端日志）。

| 代码 | 含义 | 典型触发 |
| --- | --- | --- |
| `INVALID_REQUEST` | 请求形态不合法 | 空 dataset/metrics、未知数据集、不支持的聚合类型、JSON 解析失败 |
| `UNAUTHORIZED` | 越权访问 | 角色不在字段允许角色集合内 |
| `FIELD_MISSING` | 字段缺失 | 请求字段在数据集或策略中不存在 |
| `POLICY_CONFLICT` | 策略冲突/不可执行 | 未知策略版本、字段重复、策略未覆盖数据集字段、请求被 SUPPRESS 字段、字段不可作维度/度量 |
| `BUDGET_OUT_OF_RANGE` | 单价超出账户总量 | 本次 epsilon 大于账户初始预算（物理上永远无法满足） |
| `BUDGET_EXHAUSTED` | 预算耗尽 | 单价合法但当前余额不足 |
| `INTERNAL_ERROR` | 内部失败归一化 | 取数/解析/加噪等任意未预期异常；预算已补偿退还 |

**确定性优先级**：一个请求同时存在多个问题时，按枚举顺序取最高优先级
`INVALID_REQUEST > UNAUTHORIZED > FIELD_MISSING > POLICY_CONFLICT > BUDGET_OUT_OF_RANGE > BUDGET_EXHAUSTED > INTERNAL_ERROR`
（预算检查排在全部策略检查之后，因此预算问题永远不会掩盖策略拒绝）。

HTTP 层约定：业务判定（含拒绝）返回 `200` + `allowed:false` + 稳定码，便于调用方读取 `decisionBasis` 与预算快照；请求体无法解析返回 `400 INVALID_REQUEST`；兜底未预期异常返回 `500 INTERNAL_ERROR`。

---

## 5. 验证方法

```bash
mvn test
```

| 测试类 | 覆盖边界 |
| --- | --- |
| `BudgetConcurrencyTest` | 50 线程并发扣减：恰好 10 成功/40 拒绝、余额精确归零、流水首尾相接无重复；超范围 vs 耗尽两种原因可区分 |
| `GatewayServiceIdempotencyTest` | 重复请求三次只扣一次、结果/预算快照一致；20 线程同幂等键只扣一次；四类拒绝码可区分且均 cost=0、无流水；无幂等键下同输入噪声可复现 |
| `PolicyVersionSwitchTest` | v1/v2 同输入结论各自可复现且互不相同；v2 年龄分桶+小组抑制并 `degraded=true`；升级后 v1 历史结论仍可复现；省略版本走当前版本；未知版本冲突拒绝；策略收紧后可区分拒绝 |
| `InternalFailureNormalizationTest` | 注入底层取数异常 → `INTERNAL_ERROR`、不泄露连接串等原始信息、预算补偿退还净额为 0 |
| `GatewayControllerIntegrationTest` | HTTP 正常发布、越权/预算耗尽稳定码、畸形 JSON 归一化 `INVALID_REQUEST` |

单测日志通过 SLF4J 打印**请求标识、预算 before/cost/after 变化、成功/拒绝的判定依据与原因码**，可用 `mvn test` 控制台或 `target/surefire-reports/` 查看。

---

## 6. 代码结构

```
model/      QueryRequest / QueryResponse / ResultRow / MetricSpec / RejectionCode
data/       Dataset / DataRepository（内存确定性数据）
policy/     PolicyDefinition / PolicyRegistry / FieldPolicy / FieldTransform
budget/     BudgetKey / BudgetAccount / LedgerEntry / BudgetStore（CAS + 补偿退还）
noise/      DeterministicNoise（双端几何、种子+上下文确定性）
engine/     PolicyEvaluator（不耗预算的前置判定）
            Aggregator（先脱敏后分组 + k-匿名抑制）
            Masking（版本化字段变换）
            GatewayService（幂等 -> 策略 -> 预算 -> 聚合加噪 的同一状态视图）
            GatewayException
web/        GatewayController / GatewayExceptionHandler / ApiError
config/     GatewayBootstrap（组件装配 + 数据/策略播种）
```
