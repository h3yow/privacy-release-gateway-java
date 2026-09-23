package com.github.h3yow.privacyreleasegatewayjava.engine;

import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetKey;
import com.github.h3yow.privacyreleasegatewayjava.budget.BudgetStore;
import com.github.h3yow.privacyreleasegatewayjava.data.DataRepository;
import com.github.h3yow.privacyreleasegatewayjava.data.Dataset;
import com.github.h3yow.privacyreleasegatewayjava.model.MetricSpec;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryResponse;
import com.github.h3yow.privacyreleasegatewayjava.model.RejectionCode;
import com.github.h3yow.privacyreleasegatewayjava.noise.DeterministicNoise;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldPolicy;
import com.github.h3yow.privacyreleasegatewayjava.policy.FieldTransform;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyDefinition;
import com.github.h3yow.privacyreleasegatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复请求 / 幂等 / 拒绝不耗预算 / 结论可复现 的边界测试。
 */
class GatewayServiceIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayServiceIdempotencyTest.class);

    private TestHarness harness() {
        return new TestHarness();
    }

    private QueryRequest countByCity(String rid, String idemKey, Integer version) {
        return new QueryRequest(rid, idemKey, "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city"), "analyst", version);
    }

    @Test
    void duplicateRequestsAreReplayedAndBudgetChargedOnce() {
        TestHarness h = harness();
        QueryRequest first = countByCity("req-dup-1", "idem-42", 1);
        QueryResponse r1 = h.service.release(first);
        QueryResponse r2 = h.service.release(countByCity("req-dup-2", "idem-42", 1));
        QueryResponse r3 = h.service.release(countByCity("req-dup-3", "idem-42", 1));

        assertTrue(r1.allowed());
        assertEquals(1.0d, r1.budgetCost(), 1e-9);
        assertEquals(2.0d, h.store.remaining(new BudgetKey("customers", List.of("city"))), 1e-9,
                "初始 3.0，单价 1.0，三次重复请求只扣一次");
        assertEquals(r1.rows(), r2.rows(), "重复请求结果逐行一致");
        assertEquals(r1.rows(), r3.rows(), "重复请求结果逐行一致");
        assertEquals(r1.budgetBefore(), r2.budgetBefore(), 1e-12, "预算快照回放首次结论");
        assertEquals(r1.budgetRemaining(), r3.budgetRemaining(), 1e-12);
        assertNotEquals(r1.requestId(), r2.requestId(), "每次保留各自请求标识用于追踪");
        assertEquals(1, h.store.ledger(new BudgetKey("customers", List.of("city"))).size(),
                "流水只有一条：不重复扣减");
        log.info("[{}] 重复请求判定: allowed={}, cost={}, remaining={}, rows={}, basis={}",
                r3.requestId(), r3.allowed(), r3.budgetCost(), r3.budgetRemaining(),
                r3.rows().size(), r3.decisionBasis());
    }

    @Test
    void concurrentSameIdempotencyKeyChargesExactlyOnce() throws Exception {
        TestHarness h = harness();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentLinkedQueue<QueryResponse> responses = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    responses.add(h.service.release(countByCity("req-conc-" + idx, "same-key", 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(n, responses.size());
        for (QueryResponse r : responses) {
            assertTrue(r.allowed());
            assertEquals(1.0d, r.budgetCost(), 1e-9);
            assertEquals(responses.peek().rows(), r.rows());
        }
        assertEquals(2.0d, h.store.remaining(new BudgetKey("customers", List.of("city"))), 1e-9,
                "20 个同键并发请求总共只扣一次预算");
        log.info("同键并发: requests={}, ledgerSize={}, remaining={}",
                n, h.store.ledger(new BudgetKey("customers", List.of("city"))).size(),
                h.store.remaining(new BudgetKey("customers", List.of("city"))));
    }

    @Test
    void rejectedRequestsConsumeNoBudgetAndCodesAreDistinguishable() {
        TestHarness h = harness();
        BudgetKey key = new BudgetKey("customers", List.of("city"));

        // UNAUTHORIZED：viewer 无权在 v1 访问 name
        QueryResponse unauth = h.service.release(new QueryRequest("r-unauth", "k1", "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city", "name"), "viewer", 1));
        assertFalse(unauth.allowed());
        assertEquals(RejectionCode.UNAUTHORIZED, unauth.rejectionCode());

        // FIELD_MISSING：数据集中不存在的字段
        QueryResponse missing = h.service.release(new QueryRequest("r-missing", "k2", "customers",
                List.of("city"), List.of(new MetricSpec("COUNT", null)),
                List.of("city", "phone"), "analyst", 1));
        assertEquals(RejectionCode.FIELD_MISSING, missing.rejectionCode());

        // POLICY_CONFLICT：未知策略版本
        QueryResponse badVer = h.service.release(countByCity("r-badver", "k3", 99));
        assertEquals(RejectionCode.POLICY_CONFLICT, badVer.rejectionCode());

        // INVALID_REQUEST：空 metrics
        QueryResponse invalid = h.service.release(new QueryRequest("r-invalid", "k4", "customers",
                List.of("city"), List.of(), List.of("city"), "analyst", 1));
        assertEquals(RejectionCode.INVALID_REQUEST, invalid.rejectionCode());

        // 未知数据集
        QueryResponse noDs = h.service.release(new QueryRequest("r-nods", "k5", "nope",
                List.of(), List.of(new MetricSpec("COUNT", null)), List.of(), "analyst", 1));
        assertEquals(RejectionCode.INVALID_REQUEST, noDs.rejectionCode());

        for (QueryResponse r : List.of(unauth, missing, badVer, invalid, noDs)) {
            assertEquals(0d, r.budgetCost(), 1e-12, "拒绝请求预算成本必须为 0");
            assertEquals(r.budgetBefore(), r.budgetRemaining(), 1e-12, "拒绝前后余额不变");
            assertTrue(r.rows().isEmpty());
            log.info("[{}] 拒绝 code={} detail={} budgetCost={} basis={}",
                    r.requestId(), r.rejectionCode(), r.rejectionDetail(),
                    r.budgetCost(), r.decisionBasis());
        }
        assertEquals(3.0d, h.store.remaining(key), 1e-9, "全部拒绝后预算分文未动");
        assertEquals(0, h.store.ledger(key).size(), "拒绝不产生流水");
    }

    @Test
    void sameInputSameVersionIsReproducible() {
        TestHarness h = harness();
        QueryResponse a = h.service.release(countByCity("r-a", null, 1));
        QueryResponse b = h.service.release(countByCity("r-b", null, 1));
        assertEquals(a.rows(), b.rows(), "同版本同输入（无幂等键）噪声确定性，结果一致");
        log.info("[{}] 可复现性 rows={}", a.requestId(), a.rows());
    }

    /** 轻量内存装配，避免依赖 Spring，便于隔离验证编排语义。 */
    private static final class TestHarness {
        final BudgetStore store;
        final GatewayService service;

        TestHarness() {
            DataRepository repo = new DataRepository();
            repo.register(new Dataset("customers", rows()));
            PolicyRegistry registry = new PolicyRegistry();
            registry.register(new PolicyDefinition(1, 1001L, 1.0d, 1, v1()));
            registry.register(new PolicyDefinition(2, 2002L, 1.5d, 2, v2()));
            this.store = new BudgetStore(3.0d);
            this.service = new GatewayService(registry, repo, store, new DeterministicNoise());
        }

        private static Map<String, FieldPolicy> v1() {
            Map<String, FieldPolicy> m = new LinkedHashMap<>();
            m.put("id", new FieldPolicy("id", Set.of("analyst", "admin"), FieldTransform.HASH, false, false));
            m.put("name", new FieldPolicy("name", Set.of("analyst", "admin"), FieldTransform.MASK, false, false));
            m.put("age", new FieldPolicy("age", Set.of("analyst", "admin", "viewer"), FieldTransform.NONE, true, true));
            m.put("city", new FieldPolicy("city", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, false, true));
            m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
            return Map.copyOf(m);
        }

        private static Map<String, FieldPolicy> v2() {
            Map<String, FieldPolicy> m = new LinkedHashMap<>();
            m.put("id", new FieldPolicy("id", Set.of("analyst", "admin"), FieldTransform.HASH, false, false));
            m.put("name", new FieldPolicy("name", Set.of("analyst", "admin"), FieldTransform.SUPPRESS, false, false));
            m.put("age", new FieldPolicy("age", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, true, true));
            m.put("city", new FieldPolicy("city", Set.of("analyst", "admin", "viewer"), FieldTransform.GENERALIZE, false, true));
            m.put("salary", new FieldPolicy("salary", Set.of("admin"), FieldTransform.NONE, true, false));
            return Map.copyOf(m);
        }

        private static List<Map<String, Object>> rows() {
            Object[][] data = {
                    {"c001", "Alice", 31, "Beijing", 12000},
                    {"c002", "Bob", 34, "Beijing", 13500},
                    {"c003", "Carol", 42, "Beijing", 21000},
                    {"c004", "Dave", 29, "Shanghai", 11000},
                    {"c005", "Eve", 27, "Shanghai", 9800},
                    {"c006", "Frank", 38, "Shanghai", 18000},
            };
            return java.util.Arrays.stream(data).map(row -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row[0]);
                m.put("name", row[1]);
                m.put("age", row[2]);
                m.put("city", row[3]);
                m.put("salary", row[4]);
                return m;
            }).toList();
        }
    }
}
