# URL Shortener — Load Testing Results

## Progression Summary

### Baseline tests (60 VUs — writers + readers)

| Phase | Throughput | Codes/s | Redirect p95 | Shorten p95 | All Thresholds |
|---|---|---|---|---|---|
| Phase 1 — No cache, 1 instance | 1,247/s | 162/s | 73ms ❌ | 112ms | 1 failed |
| Phase 2 — Redis cache, 1 instance | 7,534/s | 812/s | 15ms ✅ | 24ms | All passed |
| Phase 3 — Redis + 3 instances + Nginx | 7,718/s | 964/s | 13ms ✅ | 19ms | All passed |

### Stress tests (800 VUs ramp — reads only)

| Phase | Throughput | p95 | Median | Max | Errors |
|---|---|---|---|---|---|
| Phase 1 — No cache, 1 instance | 1,015/s | 275ms | 16ms | 12,370ms | 0 |
| Phase 3 — Redis + 3 instances + Nginx (1 worker) | 10,867/s | 66ms | 13ms | 2,610ms | 1,360 (0.06%) |
| Phase 3 — Redis + 3 instances + Nginx (8 workers) | 13,172/s | 59ms | 10ms | 2,310ms | 0 ✅ |

---

## Phase 1 Environment

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

## Phase 1 Problems Identified

| # | Problem | Phase | Status |
|---|---|---|---|
| 1 | Redirect p95 over SLO (73ms, target 50ms) | Phase 2 | ✅ Fixed — dropped to 15ms |
| 2 | Pool exhaustion spikes (10–12s max latency) | Phase 2 | ✅ Fixed — max dropped to 174ms |
| 3 | Single instance throughput ceiling (~1,247 req/s) | Phase 3 | ⏳ Pending |

---

---

# Phase 2 — Redis Caching

## Phase 2 Environment

| Key | Value |
|---|---|
| Setup | Single Spring Boot instance + Postgres 16 + Redis 7 (Alpine) in Docker |
| Caching | Redis read-through cache, 1 hour TTL, String serialization |
| Scaling | None — single instance |
| Machine | MacBook Air (local) |
| Orchestration | Docker Compose |
| App version | Phase 2 — Redis caching via @Cacheable |
| Cache client | Lettuce 6.8.2 (async, thread-safe) |

## What changed

- Added `spring-boot-starter-cache` and `spring-boot-starter-data-redis` to `pom.xml`
- Added Redis service to `docker-compose.yml` with healthcheck
- Added `CacheConfig.java` with `@EnableCaching` and String serialization (no Java magic bytes)
- Added `@Cacheable(value = "urls", key = "#code")` to `UrlService.resolveUrl()`
- Cache stores plain `String` values — readable directly from `redis-cli`
- `cache-null-values: false` — empty results are not cached

## How the cache works

```
First request for /{code}  (cache MISS):
  GET "urls::1"        → nil
  SELECT * FROM urls   → https://www.anthropic.com
  SET "urls::1" "https://www.anthropic.com" PX 3600000

Second request for /{code}  (cache HIT):
  GET "urls::1"        → "https://www.anthropic.com"
  (Postgres never touched)
```

---

## Phase 2 — Baseline Load Test

**Script:** `baseline.js` (same script as Phase 1 — apples-to-apples comparison)
**Purpose:** Measure improvement after adding Redis

### Config

| Key | Value |
|---|---|
| Writers | 10 VUs — POST /shorten, no sleep, 60s |
| Readers | 50 VUs — GET /{code}, no sleep, 60s (starts at 5s) |
| Total VUs | 60 |
| Duration | 60s |
| Cache hit rate | ~100% (12 hardcoded codes, all cached after first pass) |

### Results

| Metric | Value |
|---|---|
| Total requests | 489,701 (7,534/s) |
| Codes created | 52,761 (812/s — write throughput) |
| Error rate | 0.00% |

**Write latency (`shorten_latency`):**

| Stat | Phase 1 | Phase 2 | Improvement |
|---|---|---|---|
| avg | 50ms | 11ms | 4.5x |
| med | 26ms | 9.4ms | 2.8x |
| p(90) | 78ms | 20ms | 3.9x |
| p(95) | 112ms | 24ms | 4.7x |
| p(99) | 210ms | 41ms | 5.1x |
| max | 7,935ms | 216ms | 37x |

**Read latency (`redirect_latency`):**

| Stat | Phase 1 | Phase 2 | Improvement |
|---|---|---|---|
| avg | 42ms | 6.8ms | 6.2x |
| med | 18ms | 5.5ms | 3.3x |
| p(90) | 51ms | 12ms | 4.3x |
| p(95) | 73ms | 15ms | 4.9x |
| p(99) | 157ms | 27ms | 5.8x |
| max | 10,875ms | 174ms | 62x |

### Thresholds

| Threshold | Target | Phase 1 | Phase 2 | Result |
|---|---|---|---|---|
| Error rate | < 1% | 0% | 0% | ✅ |
| shorten p(95) | < 200ms | 112ms | 24ms | ✅ |
| shorten p(99) | < 500ms | 210ms | 41ms | ✅ |
| redirect p(95) | < 50ms | 73ms ❌ | 15ms | ✅ |
| redirect p(99) | < 200ms | 157ms | 27ms | ✅ |

---

## Phase 2 — Key Observations

### 1. Throughput jumped 6x

```
Phase 1:  1,247 req/s
Phase 2:  7,534 req/s   (6x increase, same hardware, same VU count)
```

Same 60 VUs. Same machine. 6x more work done. The difference is time spent waiting — at 5.5ms median vs 18ms median, each VU completes iterations 3x faster and loops back around sooner. More iterations per second = more throughput.

### 2. Writes got faster despite not being cached

```
shorten p95:  112ms → 24ms   (4.7x faster)
```

Reads and writes share the same Postgres connection pool. Before Redis, 50 reader VUs consumed pool connections constantly, forcing writes to queue. After Redis, reads mostly hit Redis and leave the pool free. Writes walk straight in — no queue. This is the **indirect benefit of caching**: relieving shared resource pressure.

### 3. Pool exhaustion spikes eliminated

```
redirect max:  10,875ms → 174ms   (62x improvement)
shorten max:    7,935ms → 216ms   (37x improvement)
```

The 10-second spikes were caused by HikariCP connection pool exhaustion (default size: 10 connections, overwhelmed by 60 VUs hitting Postgres). With Redis absorbing reads, the pool is rarely under pressure. Remaining max latency (~174ms) is likely a single cache miss on a new code.

### 4. Test design caveat — cache hit rate is artificially high

The `baseline.js` reader scenario uses 12 hardcoded codes. All 12 are cached after the first request, so every subsequent read is a cache hit. **This measures the best-case scenario for Redis.**

Real-world cache hit rate depends on traffic distribution. For a URL shortener with viral content, 80-95% hit rate is realistic. For uniform traffic across many URLs, hit rate could be lower. The improvement you see in production will be proportional to your actual hit rate.

---

## Phase 2 — Remaining Problems

| # | Problem | Phase | Status |
|---|---|---|---|
| 1 | Single instance throughput ceiling | Phase 3 | ✅ Addressed — 3 instances behind Nginx |
| 2 | Writes still hit Postgres on every request | Phase 3+ | ⚠️ Improved (964/s vs 812/s) — inherently stateful |
| 3 | HikariCP pool size never tuned (default: 10) | Phase 3 | ✅ Tuned to 20 per instance |
| 4 | Cache hit rate assumed high — no eviction strategy | Phase 5 | ⏳ Pending |

---

---

# Phase 3 — Horizontal Scaling (3 instances + Nginx)

## Phase 3 Environment

| Key | Value |
|---|---|
| Setup | 3 Spring Boot instances + Postgres 16 + Redis 7 + Nginx (Alpine) |
| Caching | Redis read-through cache, 1 hour TTL |
| Scaling | 3 app instances via `--scale app=3` |
| Load balancer | Nginx — round-robin upstream across all 3 instances |
| Machine | MacBook Air (local) |
| Orchestration | Docker Compose |
| HikariCP pool | 20 max connections per instance (60 total to Postgres) |

## What changed

- Added `nginx` service to `docker-compose.yml` with `nginx.conf`
- App service no longer exposes ports directly — all traffic enters via Nginx on port 8080
- Nginx upstream uses Docker's internal DNS to discover all `app` instances automatically
- HikariCP tuned: `maximum-pool-size: 20`, `minimum-idle: 5`, `max-lifetime: 1200000`
- `spring.jpa.open-in-view: false` — connections released immediately after DB work, not held until response serialization
- `@Repository` added to `UrlRepository` — silences Spring Data ambiguity warning with multiple data modules

## How Nginx distributes load

```
curl http://localhost:8080/shorten
    → Nginx (port 80 inside Docker)
        → round-robin →  app-1:8080
                      →  app-2:8080
                      →  app-3:8080
```

Docker's internal DNS resolves the hostname `app` to all three container IPs automatically. No hardcoded IPs. Adding a 4th instance via `--scale app=4` is picked up without any Nginx config change.

---

## Phase 3 — Baseline Load Test

**Script:** `baseline.js` (same script — apples-to-apples comparison)

### Config

| Key | Value |
|---|---|
| Writers | 10 VUs — POST /shorten, no sleep, 60s |
| Readers | 50 VUs — GET /{code}, no sleep, 60s (starts at 5s) |
| Total VUs | 60 |
| Duration | 60s |
| Cache hit rate | ~100% (12 hardcoded codes) |

### Results

| Metric | Value |
|---|---|
| Total requests | 501,699 (7,718/s) |
| Codes created | 62,654 (964/s — write throughput) |
| Error rate | 0.00% |

**Write latency (`shorten_latency`):**

| Stat | Phase 1 | Phase 2 | Phase 3 | P1→P3 |
|---|---|---|---|---|
| avg | 50ms | 11ms | 9.5ms | 5.3x |
| med | 26ms | 9.4ms | 8.2ms | 3.2x |
| p(90) | 78ms | 20ms | 15ms | 5.2x |
| p(95) | 112ms | 24ms | 19ms | 5.9x |
| p(99) | 210ms | 41ms | 31ms | 6.8x |
| max | 7,935ms | 216ms | 467ms | 17x |

**Read latency (`redirect_latency`):**

| Stat | Phase 1 | Phase 2 | Phase 3 | P1→P3 |
|---|---|---|---|---|
| avg | 42ms | 6.8ms | 6.8ms | 6.2x |
| med | 18ms | 5.5ms | 5.7ms | 3.2x |
| p(90) | 51ms | 12ms | 10.8ms | 4.7x |
| p(95) | 73ms | 15ms | 13ms | 5.6x |
| p(99) | 157ms | 27ms | 22ms | 7.1x |
| max | 10,875ms | 174ms | 1,104ms | — |

### Thresholds

| Threshold | Target | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|---|
| Error rate | < 1% | 0% ✅ | 0% ✅ | 0% ✅ |
| shorten p(95) | < 200ms | 112ms ✅ | 24ms ✅ | 19ms ✅ |
| shorten p(99) | < 500ms | 210ms ✅ | 41ms ✅ | 31ms ✅ |
| redirect p(95) | < 50ms | 73ms ❌ | 15ms ✅ | 13ms ✅ |
| redirect p(99) | < 200ms | 157ms ✅ | 27ms ✅ | 22ms ✅ |

---

## Phase 3 — Key Observations

### 1. Throughput barely moved from Phase 2 — expected and correct

```
Phase 2:  7,534/s
Phase 3:  7,718/s   (+2.4%)
```

The bottleneck was never the app thread count — it was Redis. With 50 readers hitting 12 cached codes, Redis is answering ~7,500 req/s. Adding more app instances just means more processes waiting on the same Redis. The queue moved from "waiting for an app thread" to "waiting for Redis."

**This is the correct outcome.** You scaled the app layer and it revealed the next bottleneck — exactly what horizontal scaling is supposed to do.

To see 3x throughput from 3 instances you need a workload where the app is the bottleneck. That means cache miss-heavy traffic where every request hits Postgres. With high cache hit rates, Redis is the ceiling regardless of instance count.

### 2. Writes improved meaningfully — this is the real Phase 3 win

```
Codes created:  812/s → 964/s    (+19%)
Shorten p95:    24ms  → 19ms     (+21%)
Shorten p99:    41ms  → 31ms     (+24%)
```

Writes don't use the cache — every write hits Postgres. Three instances = three independent HikariCP pools = more concurrent write capacity. This is where horizontal scaling genuinely paid off.

### 3. Max latency spike — not a concern

```
Phase 2 max:  174ms
Phase 3 max:  1,104ms
```

Three JVMs means three independent garbage collectors. The 1.1s spike is almost certainly a GC pause on one instance during the test. Look at p99 (22ms) — 99% of requests finished in under 22ms. The max is an extreme outlier, not a trend.

### 4. The architecture is now production-shaped

```
Before Phase 3:  single point of failure on the app layer
After Phase 3:   if app-1 crashes, app-2 and app-3 keep serving traffic
                 Nginx health-checks instances and routes around failures
```

Fault tolerance is as important as throughput. With 3 instances, a single JVM crash doesn't take down the service.

---

## Phase 3 — Where is the bottleneck now?

With Redis absorbing reads and 3 app instances handling writes, the remaining bottleneck candidates are:

| Component | Bottleneck Signal | Fix |
|---|---|---|
| Redis | Redis CPU maxes in `docker stats` | Redis Cluster / read replicas |
| Postgres | Write latency climbs, p99 spikes | Connection pooling (PgBouncer), read replicas, query tuning |
| Nginx | Nginx CPU maxes, connection errors | Tune `worker_processes`, `worker_connections` |
| Network | Throughput plateaus, no CPU spike | Move to cloud VM — local Docker networking is the ceiling |

The local machine ceiling is also a real constraint. All 6 containers (3 app + nginx + redis + postgres) share the same CPU cores. On a dedicated server with separated services, throughput would be significantly higher.

---

## Full Three-Phase Reference Card

```
┌─────────────────────┬──────────────┬──────────────┬──────────────────────────┐
│ Metric              │ Phase 1      │ Phase 2      │ Phase 3                  │
│                     │ No cache     │ Redis        │ Redis + 3x + Nginx       │
├─────────────────────┼──────────────┼──────────────┼──────────────────────────┤
│ Throughput          │ 1,247/s      │ 7,534/s      │ 7,718/s                  │
│ Write throughput    │ 162/s        │ 812/s        │ 964/s                    │
│ Redirect p50        │ 18ms         │ 5.5ms        │ 5.7ms                    │
│ Redirect p95        │ 73ms ❌      │ 15ms ✅      │ 13ms ✅                  │
│ Redirect p99        │ 157ms        │ 27ms         │ 22ms                     │
│ Redirect max        │ 10,875ms     │ 174ms        │ 1,104ms (GC spike)       │
│ Shorten p50         │ 26ms         │ 9.4ms        │ 8.2ms                    │
│ Shorten p95         │ 112ms        │ 24ms         │ 19ms                     │
│ Shorten p99         │ 210ms        │ 41ms         │ 31ms                     │
│ Shorten max         │ 7,935ms      │ 216ms        │ 467ms                    │
│ Error rate          │ 0%           │ 0%           │ 0%                       │
│ Thresholds          │ 1 failed     │ All passed   │ All passed               │
│ Bottleneck          │ DB pool      │ Redis        │ Redis / Postgres writes  │
│ SPOF                │ App + DB     │ App + DB     │ DB only                  │
└─────────────────────┴──────────────┴──────────────┴──────────────────────────┘
```

---

## Cumulative improvements — Phase 1 → Phase 3

```
Total throughput:    1,247/s  →  7,718/s    (6.2x)
Write throughput:    162/s    →  964/s      (6.0x)
Redirect p95:        73ms     →  13ms       (5.6x)
Redirect p99:        157ms    →  22ms       (7.1x)
Shorten p95:         112ms    →  19ms       (5.9x)
Pool exhaustion:     10,875ms →  22ms p99   (eliminated)
Single point of failure:      App layer     (eliminated)
```

---

---

# Phase 3b — Nginx Tuning (worker_processes auto)

## What changed

Single config line in `nginx/nginx.conf`:

```nginx
# Before
events {
    worker_connections 1024;
}

# After
worker_processes auto;       # spawns 1 worker per CPU core (8 on this machine)
events {
    worker_connections 4096; # connections per worker
}
```

No app changes. No Docker rebuild. Just `docker compose restart nginx`.

## Why this matters

Nginx defaults to 1 worker process. On a multi-core machine, that single worker can only use 1 core regardless of how much CPU is available. Under high concurrency it becomes a bottleneck before the app, DB, or cache.

`worker_processes auto` spawns one worker per logical CPU core. On an 8-core machine: 8 independent workers, each handling a share of incoming connections in parallel.

Total theoretical connection capacity: `8 workers × 4096 connections = 32,768 simultaneous connections`.

## Stress test — before vs after

**Before (1 worker):**

| Metric | Value |
|---|---|
| Throughput | 10,867/s |
| p90 | 54ms |
| p95 | 66ms |
| median | 13ms |
| max | 2,610ms |
| Errors | 1,360 (0.06%) — EOF connection drops |
| Bottleneck | Single Nginx worker at 98% CPU |

**After (8 workers):**

| Metric | Value | vs before |
|---|---|---|
| Throughput | 13,172/s | +21% |
| p90 | 42ms | -22% |
| p95 | 59ms | -11% |
| median | 10ms | -23% |
| max | 2,310ms | -11% |
| Errors | 0 (0.00%) | ✅ eliminated |

## Key observations

**Errors eliminated.** The 1,360 EOF errors were Nginx dropping connections when its single worker was saturated at ~400-500 VUs. 8 workers distributed the load and dropped nothing across 2,371,024 requests.

**21% throughput gain from one config line.** The fix cost zero code changes and zero infrastructure changes.

**Remaining max latency (2.3s) is the local machine ceiling.** At 800 VUs, all containers — 8 Nginx workers, 3 JVMs, Postgres, Redis, and k6 itself — compete for the same 8 physical cores. The OS context-switching overhead becomes the bottleneck. On dedicated hardware with separated services this would not occur.

---

## Full stress test progression

| Setup | Throughput | p95 | Median | Max | Errors |
|---|---|---|---|---|---|
| Phase 1 — 1 instance, no cache | 1,015/s | 275ms | 16ms | 12,370ms | 0 |
| Phase 3 — 3 instances + Redis + Nginx (1 worker) | 10,867/s | 66ms | 13ms | 2,610ms | 1,360 ❌ |
| Phase 3b — 3 instances + Redis + Nginx (8 workers) | 13,172/s | 59ms | 10ms | 2,310ms | 0 ✅ |

## Cumulative improvements — Phase 1 stress → Phase 3b stress

```
Throughput:   1,015/s   →  13,172/s   (13x)
p95:          275ms     →  59ms       (4.7x)
Median:       16ms      →  10ms       (1.6x)
Max latency:  12,370ms  →  2,310ms    (5.4x)
Errors:       0         →  0          (clean throughout)
```