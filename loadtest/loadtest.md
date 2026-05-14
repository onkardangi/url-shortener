# URL Shortener — Load Testing Results

## Environment

| Key | Value |
|---|---|
| Setup | Single Spring Boot instance + Postgres 16 (Alpine) in Docker |
| Caching | None |
| Scaling | None — single instance |
| Machine | MacBook Air (local) |
| Orchestration | Docker Compose — app + postgres on same host |
| App version | Phase 1 — base implementation |

---

## Test 1: Smoke Test

**Script:** `smoke.js`  
**Purpose:** Verify correctness under minimal load before stressing anything

### Config

| Key | Value |
|---|---|
| VUs | 1 |
| Duration | 30s |
| Sleep | 1s between iterations |
| Pattern | Each iteration = 1 POST /shorten + 1 GET /{code} |

### Results

| Metric | Value |
|---|---|
| Total requests | 60 (1.95/s) |
| Iterations | 30 (0.97/s) |
| Checks passed | 100% (90/90) |
| Error rate | 0.00% |

**Latency:**

| Stat | Value |
|---|---|
| avg | 12.23ms |
| min | 3.74ms |
| med | 6.62ms |
| p(90) | 8.73ms |
| p(95) | 11.14ms |
| max | 340.59ms ← JVM cold start, first request only |

### Thresholds

| Threshold | Target | Actual | Result |
|---|---|---|---|
| p(95) latency | < 500ms | 11ms | ✅ |
| Error rate | < 1% | 0% | ✅ |

### Verdict

✅ App is functionally correct. Cold start penalty ~340ms on first request, then drops to single digits. Baseline latency at 1 VU is 6–12ms per request.

---

## Test 2: Baseline Load Test

**Script:** `baseline.js`  
**Purpose:** Establish performance baseline under realistic concurrent load before any optimization

### Config

| Key | Value |
|---|---|
| Writers | 10 VUs — POST /shorten, no sleep, 60s |
| Readers | 50 VUs — GET /{code}, no sleep, 60s (starts at 5s) |
| Total VUs | 60 |
| Duration | 60s |
| Pattern | Realistic read-heavy skew (5:1 readers to writers) |

### Results

| Metric | Value |
|---|---|
| Total requests | 90,972 (1,247/s) |
| Codes created | 11,805 (162/s — write throughput) |
| Read throughput | ~1,085/s |
| Error rate | 0.00% |

**Write latency (`shorten_latency`):**

| Stat | Value |
|---|---|
| avg | 50ms |
| min | 1.68ms |
| med | 26ms |
| p(90) | 78ms |
| p(95) | 112ms |
| p(99) | 210ms |
| max | 7,935ms |

**Read latency (`redirect_latency`):**

| Stat | Value |
|---|---|
| avg | 42ms |
| min | 0.44ms |
| med | 18ms |
| p(90) | 51ms |
| p(95) | 73ms |
| p(99) | 157ms |
| max | 10,875ms |

### Thresholds

| Threshold | Target | Actual | Result |
|---|---|---|---|
| Error rate | < 1% | 0% | ✅ |
| shorten p(95) | < 200ms | 112ms | ✅ |
| shorten p(99) | < 500ms | 210ms | ✅ |
| redirect p(95) | < 50ms | 73ms | ❌ FAILED |
| redirect p(99) | < 200ms | 157ms | ✅ |

### Key Observations

- **1,247 req/s** is the single-instance throughput ceiling on this hardware
- Redirect p95 (73ms) exceeds the 50ms SLO — every read hits Postgres with no cache
- p99-to-max spike (157ms → 10,875ms) is classic HikariCP connection pool exhaustion — default pool size is 10, overwhelmed by 60 concurrent VUs
- Write latency (p95=112ms) is higher than read latency (p95=73ms) as expected — INSERT + read-back ID is more expensive than SELECT by PK

---

## Test 3: Stress Test

**Script:** `stress.js`  
**Purpose:** Find the breaking point — where does the single instance fall over?

### Config

| Stage | Duration | VUs |
|---|---|---|
| Stage 1 | 30s | 10 → 50 |
| Stage 2 | 30s | 50 → 100 |
| Stage 3 | 30s | 100 → 200 |
| Stage 4 | 30s | 200 → 400 |
| Stage 5 | 30s | 400 → 800 |
| Stage 6 | 30s | 800 → 0 (ramp down) |

Pattern: Reads only (GET /{code}), ramping-vus executor

### Results

| Metric | Value |
|---|---|
| Total requests | 182,693 (1,015/s) |
| Error rate | 0.00% |

**Latency:**

| Stat | Value |
|---|---|
| avg | 271ms |
| min | 0.42ms |
| med | 16ms |
| p(90) | 167ms |
| p(95) | 275ms |
| max | 12,370ms |

### Verdict

✅ Zero errors even at 800 VUs. App did not crash or throw 500s.

---

## Stress Test Deep Analysis

### Median vs average gap

```
med:   16ms
avg:  271ms   (17x higher than median)
```

The enormous gap between median and average reveals a heavily skewed distribution. Most requests are fast; a small number of very slow requests drag the average up. **Average is a misleading metric here — median and percentiles tell the real story.**

### Degradation curve

| VU Range | Behaviour |
|---|---|
| 10–50 VUs | Fast. Pool handles load comfortably. med ~5ms |
| 50–200 VUs | Latency creeps up. Pool starts queuing requests. med ~15ms |
| 200–400 VUs | Noticeable slowdown. Queue backs up. p90 crosses 100ms |
| 400–800 VUs | Heavy queuing. p95=275ms. Some requests wait 12s for a connection |
| Ramp down | Latency recovers immediately — proves bottleneck is concurrency, not a leak |

### Why zero errors despite 12-second spikes?

HikariCP's default connection timeout is 30 seconds. Requests queued waiting for a DB connection will wait up to 30s before throwing an error. At 800 VUs the queue was deep but not deep enough to breach the 30s timeout — so everything eventually succeeded, just slowly.

Pushing to ~1,500+ VUs would trigger `SQLTransientConnectionException: Unable to acquire JDBC Connection` as the timeout is breached.

### The real bottleneck: Postgres connections, not CPU

The app stayed alive at 800 VUs because the bottleneck is **I/O wait** (waiting for Postgres), not CPU. JVM threads are mostly blocked waiting for DB responses, not burning cycles. This is why:

- The app did not crash or OOM
- Error rate stayed 0%
- But latency climbed to 12 seconds at peak

If the bottleneck were CPU-bound work (e.g. image processing, encryption), errors and crashes would appear much earlier.

---

## Baseline Summary — Reference Card

| Metric | Value | Note |
|---|---|---|
| Max throughput | ~1,247 req/s | At 60 VUs |
| Write throughput | ~162 writes/s | 10 writer VUs |
| Read throughput | ~1,085 reads/s | 50 reader VUs |
| Redirect p50 | 18ms | |
| Redirect p95 | 73ms | ❌ over 50ms SLO |
| Redirect p99 | 157ms | |
| Redirect max | 10,875ms | Pool exhaustion |
| Shorten p50 | 26ms | |
| Shorten p95 | 112ms | |
| Shorten p99 | 210ms | |
| Shorten max | 7,935ms | Pool exhaustion |
| Error rate | 0.00% | |
| Hard crash point | Not reached | Survived 800 VUs |
| Degradation onset | ~200 VUs | |
| Bottleneck | HikariCP pool | Default size: 10 |
| Stress total reqs | 182,693 | 0 errors |
| Stress median | 16ms | Real typical experience |
| Stress p95 | 275ms | At peak 800 VUs |
| Stress max | 12,370ms | |

---

## Problems Identified

| # | Problem | Phase | Fix |
|---|---|---|---|
| 1 | Redirect p95 over SLO (73ms, target 50ms) | Phase 2 | Redis read-through cache |
| 2 | Pool exhaustion spikes (10–12s max latency) | Phase 2 | Redis reduces DB load + tune HikariCP pool size |
| 3 | Single instance throughput ceiling (~1,247 req/s) | Phase 3 | Multiple app instances behind Nginx |

---

## Expected Improvements Per Phase

### After Phase 2 — Redis caching

| Metric | Before | Expected After |
|---|---|---|
| Redirect p95 | 73ms | < 5ms |
| Pool exhaustion spikes | Present | Gone |
| Overall throughput | ~1,247/s | Higher (less time waiting on DB) |

Cached reads skip Postgres entirely. DB load drops ~80%, relieving pool pressure.

### After Phase 3 — Horizontal scaling (3 instances)

| Metric | Before | Expected After |
|---|---|---|
| Total throughput | ~1,247/s | ~3,000+/s |
| Write p95 | 112ms | Lower (load distributed) |
| Breaking point | App pool | Moves to Nginx or Postgres |

Throughput scales roughly linearly with instance count. New bottleneck becomes the load balancer or the DB, not the app.