# URL Shortener

A production-style URL shortener built with Spring Boot and PostgreSQL. Designed in phases to progressively add caching and horizontal scaling, with load testing at each phase to measure real impact.

## How it works

1. `POST /shorten` — accepts a long URL, persists it, and returns a short code
2. `GET /{code}` — decodes the code back to a database ID, looks up the original URL, and issues a `301` redirect

Short codes are generated using **Base62 encoding** of the auto-incremented database ID — no collision risk, no random generation needed.

```
ID 1     → "1"
ID 62    → "10"
ID 3844  → "100"
```

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 4 |
| Web | Spring MVC |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Containerization | Docker + Docker Compose |
| Load Testing | k6 |
| Unit Testing | JUnit 5, Mockito, MockMvc |

## API

### Shorten a URL

```
POST /shorten
Content-Type: application/json

{ "url": "https://example.com/some/long/path" }
```

```json
{
  "shortUrl": "http://localhost:8080/1",
  "code": "1",
  "longUrl": "https://example.com/some/long/path"
}
```

### Redirect

```
GET /{code}

→ 301 Location: https://example.com/some/long/path
→ 404 if code not found
```

## Running locally

### Option 1 — Docker Compose (recommended)

Starts both the app and PostgreSQL in containers. No local database needed.

```bash
docker compose up --build
```

App is available at `http://localhost:8080`.

### Option 2 — Run against a local PostgreSQL

1. Copy the example env file and fill in your local credentials:
   ```bash
   cp .env.example .env
   ```

2. Create the database:
   ```bash
   psql -U <your-user> -c "CREATE DATABASE shortener;"
   ```

3. Run the app:
   ```bash
   ./mvnw spring-boot:run
   ```

Flyway will run the migration at startup and create the `urls` table automatically.

## Running tests

```bash
./mvnw test
```

18 tests across three classes:

| Class | Type | Tests |
|---|---|---|
| `Base62EncoderTest` | Unit | 8 |
| `UrlServiceTest` | Unit (Mockito) | 4 |
| `UrlControllerTest` | Web slice (`@WebMvcTest`) | 5 |
| `UrlShortenerApplicationTests` | Context load | 1 |

## Load Testing

Scripts are in the `loadtest/` directory and use [k6](https://k6.io).

| Script | Purpose |
|---|---|
| `smoke.js` | 1 VU, 30s — verify correctness before stressing |
| `baseline.js` | 60 VUs (10 writers + 50 readers), 60s — establish throughput baseline |
| `stress.js` | Ramp 10 → 800 VUs — find the breaking point |

```bash
k6 run loadtest/smoke.js
k6 run loadtest/baseline.js
k6 run loadtest/stress.js
```

### Phase 1 Baseline Results (single instance, no cache)

| Metric | Value |
|---|---|
| Max throughput | ~1,247 req/s |
| Redirect p50 | 18ms |
| Redirect p95 | 73ms ❌ (SLO: 50ms) |
| Shorten p95 | 112ms |
| Error rate | 0.00% |
| Hard crash point | Not reached (survived 800 VUs) |
| Bottleneck | HikariCP connection pool (default size: 10) |

Full results and analysis: [`loadtest/loadtest.md`](loadtest/loadtest.md)

## Project Roadmap

| Phase | Focus | Status |
|---|---|---|
| Phase 1 | Core implementation — shorten + redirect + Flyway + Docker | ✅ Done |
| Phase 2 | Redis read-through cache — target redirect p95 < 5ms | Planned |
| Phase 3 | Horizontal scaling — multiple app instances behind Nginx | Planned |

## Project Structure

```
src/main/java/com/learning/url_shortener/
├── controller/UrlController.java     # POST /shorten, GET /{code}
├── service/UrlService.java           # shorten + resolve logic
├── service/Base62Encoder.java        # ID → short code encoding
├── repository/UrlRepository.java     # JPA repository
├── entity/Url.java                   # urls table entity
└── dto/
    ├── ShortenRequest.java
    └── ShortenResponse.java

src/main/resources/
├── application.yaml                  # config with env var fallbacks
└── db/migration/V1__init.sql         # Flyway schema

loadtest/
├── smoke.js
├── baseline.js
├── stress.js
└── loadtest.md                       # full results + analysis
```
