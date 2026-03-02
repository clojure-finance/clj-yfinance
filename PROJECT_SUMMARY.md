# clj-yfinance — Project Summary

A developer-oriented reference for the architecture, internals, and maintenance of clj-yfinance. For user-facing documentation and usage examples, see [README.md](README.md).

---

## Overview

clj-yfinance is a pure Clojure library for fetching financial data from Yahoo Finance. It requires no Python bridge, no API keys, and no external HTTP dependencies — just Clojure and the `java.net.http.HttpClient` that ships with JDK 11+. The only runtime dependency is [charred](https://github.com/cnuernber/charred) for JSON parsing.

The library is organised into two tiers:

- **Stable core** — current prices, historical OHLCV, dividends, splits, and ticker metadata via Yahoo's public chart endpoint (v8). These have been reliable for years and require no authentication.
- **Experimental** — company fundamentals, analyst data, financial statements, earnings calendar, and options chains via Yahoo's authenticated `quoteSummary` and v7 options endpoints. These work today but may break if Yahoo changes their authentication mechanism.

**Target use case:** Clojure-based financial applications and research workflows needing reliable market data without the complexity of API keys, authentication, or Python interop.

---

## Environment

| Item | Value |
|---|---|
| Project type | deps.edn library (also provides `project.clj` for Leiningen) |
| Clojure | 1.11.1+ |
| Java | 11+ (requires `java.net.http.HttpClient`) |
| Runtime dependency | `com.cnuernber/charred` 1.038 (JSON parsing) |
| nREPL port | 7888 (dev only) |

---

## File Structure

### Source

| Path | Purpose |
|---|---|
| `src/clj_yfinance/core.clj` | Public API — 10 functions (5 simple + 5 verbose) |
| `src/clj_yfinance/http.clj` | HTTP client, retry logic, request/response pipeline |
| `src/clj_yfinance/parse.clj` | JSON parsing, URL encoding, data transformation |
| `src/clj_yfinance/validation.clj` | Input validation (strict) and interval/range warnings (permissive) |
| `src/clj_yfinance/dataset.clj` | Dataset integration (optional; requires tech.ml.dataset) |
| `src/clj_yfinance/kindly.clj` | Kindly-tagged dataset wrappers (optional; requires Kindly + tech.ml.dataset) |
| `src/clj_yfinance/parquet.clj` | Parquet save/load (optional; requires tmd-parquet + tech.ml.dataset) |
| `src/clj_yfinance/duckdb.clj` | DuckDB integration (optional; requires tmducken + tech.ml.dataset) |
| `src/clj_yfinance/experimental/auth.clj` | Cookie/crumb session management for Yahoo authentication |
| `src/clj_yfinance/experimental/fundamentals.clj` | Fundamentals, company info, analyst data, financials, calendar |
| `src/clj_yfinance/experimental/options.clj` | Options chains via the v7 endpoint |

### Examples & Config

| Path | Purpose |
|---|---|
| `examples/finance_demo.clj` | Clay notebook: fetch → tablecloth → tableplot → HTML export |
| `deps.edn` | Clojure CLI project file |
| `project.clj` | Leiningen project file |
| `build.clj` | Build & deploy tasks (`jar`, `deploy`, `clean`) via tools.build + deps-deploy |

### Tests

| Path | Assertions | Notes |
|---|---|---|
| `test/clj_yfinance/core_test.clj` | 105 | Core parsing, validation, URL encoding |
| `test/clj_yfinance/dataset_test.clj` | — | Requires tech.ml.dataset |
| `test/clj_yfinance/parquet_test.clj` | 15 | 4 tests; requires tmd-parquet |
| `test/clj_yfinance/duckdb_test.clj` | — | 6 tests; requires tmducken + native libduckdb |
| `test/clj_yfinance/experimental/auth_test.clj` | 14 | 5 tests |
| `test/clj_yfinance/experimental/fundamentals_test.clj` | 61 | 6 tests |
| `test/clj_yfinance/experimental/options_test.clj` | 66 | 4 tests |

All tests are pure — no network calls. Parsing is tested with JSON fixtures; `reify` mocks stand in for HTTP responses.

---

## Architecture

### Dual API Pattern

Every public function has two variants:

- **Simple** (`fetch-price`, `fetch-historical`, …) — extracts `:data` on success, returns `nil`/`[]` on failure.
- **Verbose** (`fetch-price*`, `fetch-historical*`, …) — returns a consistent result envelope:

```clojure
;; Success
{:ok?      true
 :data     ...
 :request  {:ticker "AAPL" :query-params {...}}
 :warnings [...]}   ; non-fatal issues (e.g. interval/range mismatch)

;; Failure
{:ok?   false
 :error {:type :rate-limited :ticker "AAPL" :url "..." :status 429 :message "..."}}
```

The verbose API is designed for retry logic (distinguish `:rate-limited` from `:http-error`), partial success reporting (`fetch-prices*` returns per-ticker results), and debugging (`:request` metadata always included).

### Unified Fetch Pipeline

All core fetch functions — `fetch-price*`, `fetch-historical*`, `fetch-dividends-splits*`, `fetch-info*` — are thin wrappers over a single internal `fetch-chart*` function. This gives cross-cutting concerns (caching, instrumentation, retry) a single place to live and ensures consistent error handling across all endpoints.

### Input Validation

Validation runs before any network call and has two layers:

- **`validate-opts`** (strict) — checks period/interval against allowed values, enforces that `:end` requires `:start`, validates epoch types and ordering. Returns `:invalid-opts` immediately (< 1ms), saving a network round-trip.
- **`interval-range-warnings`** (permissive) — flags technically valid but potentially problematic combinations (e.g. `1m` interval over a 30-day range). Does not fail; warnings are returned in the `:warnings` key and the request proceeds.

### HTTP Layer

The HTTP layer uses a single lazily-initialised `HttpClient` with a 10-second connection timeout and a 15-second per-request timeout. On failure it retries up to 3 times with exponential backoff (250ms base, plus jitter to avoid thundering herd). Retryable conditions: `:rate-limited` (429), `:connection-error`, HTTP 5xx. Non-retryable: 4xx (except 429). Requests automatically fall back between `query1.finance.yahoo.com` and `query2.finance.yahoo.com`.

Parallel fetches (`fetch-prices*`) use a bounded executor with configurable thread count (default 8). Each future has a 20-second timeout; `ExecutionException`, `InterruptedException`, and `TimeoutException` are caught and converted to structured per-ticker errors. The executor shuts down gracefully with a 5-second wait before `shutdownNow()`.

### Parsing and Key Normalisation

JSON is parsed by charred with keywordized keys (`{:key-fn keyword}`). Yahoo's camelCase response keys are converted to idiomatic kebab-case by `camel->kebab` / `kebabize-keys`, with a `key-exceptions` map for edge cases (e.g. `"gmtoffset"` → `:gmt-offset`). OHLCV data is extracted from the nested `:indicators :quote` structure with comprehensive validation of timestamps, quotes, and required fields. Empty result arrays return `:no-data` rather than `{:ok? true :data nil}`.

### Experimental Authentication

The experimental namespaces share a singleton session held in an atom in `clj-yfinance.experimental.auth`:

```clojure
{:http-client    <HttpClient with CookieManager>
 :cookie-handler <CookieManager>
 :crumb          "Zcwe7K.UzyF"
 :created-at     1771295552000
 :status         :active}   ; or :uninitialized, :failed
```

On first use the library fetches a session cookie from `fc.yahoo.com` and a crumb token from Yahoo's API, caches the session, and refreshes it after one hour. On 401 responses the session is refreshed once automatically; beyond that, errors surface as `:auth-failed`.

### Error Types

| Type | Cause | Retryable? |
|---|---|---|
| `:rate-limited` | HTTP 429 | Yes |
| `:http-error` | Non-200 status (e.g. 404 for unknown ticker) | No (except 5xx) |
| `:api-error` | Yahoo returned an error in the response body | No |
| `:no-data` | Empty result array from the API | No |
| `:parse-error` | JSON parsing failed | No |
| `:connection-error` | Network/socket exception | Yes |
| `:missing-price` | Valid response but price field absent | No |
| `:missing-data` | Valid response but historical data absent | No |
| `:missing-metadata` | Valid response but metadata fields absent | No |
| `:invalid-opts` | Invalid input parameters (caught pre-network) | No |
| `:timeout` | Future timed out during parallel fetch | — |
| `:execution-error` | Exception during parallel fetch task | — |
| `:interrupted` | Thread interrupted during parallel fetch | — |
| `:exception` | Unexpected exception | — |
| `:auth-failed` | Cookie/crumb refresh failed (experimental) | No |
| `:session-failed` | Session could not initialise (experimental) | No |
| `:request-failed` | Network error during auth request (experimental) | No |

---

## Optional Integrations

All integrations are opt-in via deps.edn aliases. The core library has no dependency on any of these.

| Integration | Alias | Extra Dependencies |
|---|---|---|
| tech.ml.dataset | `:dataset` | `techascent/tech.ml.dataset` |
| Kindly (Clay/Portal) | `:kindly` | `org.scicloj/kindly`, `techascent/tech.ml.dataset` |
| Parquet | `:parquet` | `com.techascent/tmd-parquet`, `techascent/tech.ml.dataset` |
| DuckDB | `:duckdb` | `com.techascent/tmducken`, `techascent/tech.ml.dataset` + native `libduckdb` |
| Noj | `:noj` | `org.scicloj/noj` |
| Clay demo | `:clay` | Clay, tablecloth, tableplot, tech.ml.dataset |

---

## Test Coverage

Tests cover: URL encoding, query string building, epoch time conversion, interval/range validation, input validation (periods, intervals, start/end ordering), time parameter construction, JSON parsing for all success and error branches, result envelope constructors, retry logic (retryable vs non-retryable), key normalisation (camelCase → kebab-case), future timeout handling, dataset column types, Parquet round-trips, and DuckDB load/query correctness.

```bash
# Core
clojure -M:test -e "(require 'clj-yfinance.core-test) (clj-yfinance.core-test/run-tests)"

# Auth
clojure -M:test -e "(require 'clj-yfinance.experimental.auth-test) (clj-yfinance.experimental.auth-test/run-tests)"

# Fundamentals
clojure -M:test -e "(require 'clj-yfinance.experimental.fundamentals-test) (clj-yfinance.experimental.fundamentals-test/run-tests)"

# Options
clojure -M:test -e "(require 'clj-yfinance.experimental.options-test) (clj-yfinance.experimental.options-test/run-tests)"

# Dataset
clojure -M:test:dataset -e "(require 'clj-yfinance.dataset-test) (clj-yfinance.dataset-test/run-tests)"

# Parquet
clojure -M:test:parquet -e "(require 'clj-yfinance.parquet-test) (clj-yfinance.parquet-test/run-tests)"

# DuckDB
clojure -M:test:duckdb -e "(require 'clj-yfinance.duckdb-test) (clj-yfinance.duckdb-test/run-tests)"
```

---

## Publishing

**Published on Clojars** as `com.github.clojure-finance/clj-yfinance`.  
**GitHub repository:** https://github.com/clojure-finance/clj-yfinance

### Build & Deploy

The project uses `tools.build` + `deps-deploy` for packaging and deployment via the `:build` alias and `build.clj`.

**To deploy a new version:**

1. Bump `version` in `build.clj`
2. Delete any stale `pom.xml` from the project root (`rm pom.xml`)
3. Set `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (deploy token) in your shell
4. Run `clj -T:build deploy`

**Important notes:**

- `build.clj` copies the generated POM to the project root (required by `deps-deploy`)
- The POM must include a license — added via `:pom-data` in `b/write-pom`
- If a `pom.xml` already exists, `tools.build` uses it as a template and ignores `:pom-data`, so always delete it before re-deploying
- `CLOJARS_*` env vars must be set in the shell that runs `clj -T:build deploy`

---

## Limitations

- **No built-in caching** — every call hits the network. Add `core.memoize` or similar at the application level.
- **No built-in rate limiting** — aggressive parallel use triggers 429 errors. Use `:concurrency` and retry logic via the verbose API.
- **Unofficial API** — Yahoo does not publicly document these endpoints. Check their Terms of Service for commercial use.
- **Financial statement coverage** — Yahoo restricts some balance sheet and cash flow fields. The income statement is the most complete module.

---

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
