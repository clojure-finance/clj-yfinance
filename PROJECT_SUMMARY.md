# clj-yfinance — Project Summary

## Overview

clj-yfinance is a pure Clojure library for fetching financial data from Yahoo Finance. It requires no Python bridge, no API keys, and no external HTTP dependencies beyond JSON parsing — just Clojure and the `java.net.http.HttpClient` that ships with JDK 11+.

The library is organised into two tiers. The **stable core** covers current prices, historical OHLCV data, dividends, splits, and basic ticker metadata via Yahoo's public chart endpoint (v8). These endpoints have been stable for years and require no authentication. The **experimental namespace** adds company fundamentals, analyst data, financial statements, and options chains via Yahoo's authenticated `quoteSummary` and v7 options endpoints. These work reliably today but Yahoo can change or revoke their authentication mechanism at any time without notice — treat them as best-effort rather than production-grade.

**Target use case:** Clojure-based financial applications and research workflows needing reliable price and historical data without the complexity of authentication, API keys, or Python interop.

---

## Environment

| Item | Value |
|---|---|
| Project type | deps.edn library (also provides `project.clj` for Leiningen) |
| Clojure | 1.11.1+ |
| Java | 11+ (requires `java.net.http.HttpClient`) |
| Runtime dependency | `cheshire/cheshire` 6.1.0 (JSON parsing) |
| nREPL port | 7888 (dev only) |

---

## File Structure

| Path | Purpose |
|---|---|
| `src/clj_yfinance/core.clj` | Public API — 10 functions (5 simple + 5 verbose) |
| `src/clj_yfinance/dataset.clj` | Optional dataset integration (requires tech.ml.dataset) |
| `src/clj_yfinance/http.clj` | HTTP client, retry logic, request/response pipeline |
| `src/clj_yfinance/parse.clj` | Pure JSON parsing, URL encoding, data transformation |
| `src/clj_yfinance/validation.clj` | Input validation (strict) and interval/range warnings (permissive) |
| `src/clj_yfinance/experimental/auth.clj` | Cookie/crumb session management for Yahoo authentication |
| `src/clj_yfinance/experimental/fundamentals.clj` | `fetch-fundamentals`, `fetch-company-info`, `fetch-analyst`, `fetch-financials`, `fetch-quotesummary*` |
| `src/clj_yfinance/experimental/options.clj` | `fetch-options`, `fetch-options*` — options chains via the v7 endpoint |
| `test/clj_yfinance/core_test.clj` | Unit tests for core functions (105 assertions, no network calls) |
| `test/clj_yfinance/dataset_test.clj` | Unit tests for dataset conversions (requires tech.ml.dataset) |
| `test/clj_yfinance/experimental/auth_test.clj` | Unit tests for auth session logic (5 tests, 14 assertions) |
| `test/clj_yfinance/experimental/fundamentals_test.clj` | Unit tests for fundamentals parsing (6 tests, 61 assertions) |
| `test/clj_yfinance/experimental/options_test.clj` | Unit tests for options parsing (4 tests, 66 assertions) |
| `deps.edn` | Clojure CLI project file |
| `project.clj` | Leiningen project file |
| `README.md` | User-facing documentation with usage examples |

---

## Public API

All stable functions live in the `clj-yfinance.core` namespace. Every function follows a **dual API design**:

- **Simple API** (`fetch-price`, `fetch-historical`, …) — returns data directly, or `nil`/`[]` on failure. Minimal code, easy to use.
- **Verbose API** (`fetch-price*`, `fetch-historical*`, …) — returns `{:ok? true :data …}` or `{:ok? false :error {…}}`. Use this when you need to distinguish error types, implement retries, or handle partial failures.

### Current Prices

```clojure
;; Single ticker
(fetch-price "AAPL")           ; => 261.05
(fetch-price* "AAPL")          ; => {:ok? true :data 261.05 :request {...}}

;; Multiple tickers in parallel (bounded thread pool, default 8 concurrent requests)
(fetch-prices ["AAPL" "GOOGL" "MSFT"])
; => {"AAPL" 261.05, "GOOGL" 337.28, "MSFT" 428.04}

(fetch-prices ["AAPL" "GOOGL"] :concurrency 2)   ; lower concurrency to reduce rate limiting

;; Verbose parallel — per-ticker success/failure
(fetch-prices* ["AAPL" "INVALID"])
; => {"AAPL"    {:ok? true  :data 261.05}
;     "INVALID" {:ok? false :error {:type :http-error :status 404 ...}}}
```

### Historical Data

```clojure
(fetch-historical "AAPL" :period "1mo")
; => [{:timestamp 1704067200 :open 185.2 :high 186.1 :low 184.0
;      :close 185.5 :volume 12345678 :adj-close 184.9} ...]

;; Custom date range with intraday interval
(fetch-historical "TSLA"
                  :start (.minus (java.time.Instant/now)
                                 (java.time.Duration/ofDays 7))
                  :interval "1h"
                  :prepost true)
```

**`:period`** — `1d` `5d` `1mo` `3mo` `6mo` `1y` `2y` `5y` `10y` `ytd` `max`  
**`:interval`** — `1m` `2m` `5m` `15m` `30m` `60m` `90m` `1h` `1d` `5d` `1wk` `1mo` `3mo`  
**`:start` / `:end`** — epoch seconds (integer) or `java.time.Instant`; `:start` overrides `:period`  
**`:adjusted`** — include adjusted close (default `true`)  
**`:prepost`** — include pre/post market data (default `false`)

### Dividends & Splits

```clojure
(fetch-dividends-splits "AAPL" :period "10y")
; => {:dividends {1699574400 {:amount 0.24 :date 1699574400} ...}
;     :splits    {1598832000 {:numerator 4 :denominator 1 ...} ...}}
```

Accepts the same `:period`, `:start`, `:end` options as `fetch-historical`. Default period is `"5y"`.

### Ticker Info

```clojure
(fetch-info "AAPL")
; => {:symbol "AAPL"
;     :long-name "Apple Inc."
;     :currency "USD"
;     :exchange-name "NMS"
;     :regular-market-price 261.05
;     :regular-market-volume 92443408
;     :fifty-two-week-high 288.62
;     :fifty-two-week-low 169.21
;     :timezone "America/New_York" ...}
```

Returns basic metadata from Yahoo's public chart endpoint. For richer company data (sector, description, P/E, officers), use `fetch-company-info` in the experimental namespace.

---

## Architecture

### Dual API Pattern

Every public function has a simple variant that extracts `:data` on success and returns `nil`/`[]` on failure, and a verbose variant that returns a consistent result envelope:

```clojure
{:ok?     true
 :data    ...
 :request {:ticker "AAPL" :query-params {...}}
 :warnings [...]}   ; non-fatal issues, e.g. interval/range mismatch

{:ok?  false
 :error {:type :rate-limited :ticker "AAPL" :url "..." :status 429 :message "..."}}
```

This makes the verbose API well-suited for retry logic (distinguish `:rate-limited` from `:http-error`), partial success reporting (`fetch-prices*` returns per-ticker results), and debugging (`:request` metadata always included).

### Unified Fetch Pipeline

All public fetch functions — `fetch-price*`, `fetch-historical*`, `fetch-dividends-splits*`, `fetch-info*` — are thin wrappers over a single internal `fetch-chart*` function. This gives cross-cutting concerns (caching, instrumentation, retry) a single place to live, and ensures consistent error handling across all endpoints.

### Input Validation

Validation runs before any network call and has two layers:

- **`validate-opts`** (strict) — checks period/interval against allowed values, enforces that `:end` requires `:start`, validates epoch types and ordering. Returns an `:invalid-opts` error immediately (< 1ms) on failure, saving a network round-trip.
- **`interval-range-warnings`** (permissive) — flags technically valid but potentially problematic combinations (e.g. `1m` interval over a 30-day range). Does not fail; warnings are returned in the `:warnings` key and the request proceeds. Yahoo's API will reject truly invalid combinations with its own error.

### HTTP Layer

The HTTP layer uses a single lazily-initialised `HttpClient` with a 10-second connection timeout and a 15-second per-request timeout. On failure it retries up to 3 times with exponential backoff (250ms base, plus jitter to avoid thundering herd) before giving up. Retryable conditions are `:rate-limited` (429), `:connection-error`, and HTTP 5xx; 4xx responses (except 429) are not retried. Requests automatically fall back between `query1.finance.yahoo.com` and `query2.finance.yahoo.com`.

Parallel fetches (`fetch-prices*`) use a bounded executor with a configurable thread count (default 8). Each future has a 20-second timeout; `ExecutionException`, `InterruptedException`, and `TimeoutException` are all caught and converted to structured per-ticker errors. The executor shuts down gracefully with a 5-second wait before a forced `shutdownNow()`.

### Parsing and Key Normalisation

JSON is parsed by Cheshire with keywordized keys. Yahoo's camelCase response keys are converted to idiomatic kebab-case by `camel->kebab` / `kebabize-keys`, with a `key-exceptions` map handling edge cases (e.g. `"gmtoffset"` → `:gmt-offset`). OHLCV data is extracted from the nested `:indicators :quote` structure, with comprehensive validation of timestamps, the quotes object, and all required fields before returning. An empty result array returns `:no-data` rather than `{:ok? true :data nil}`.

### Error Types

| Type | Cause |
|---|---|
| `:rate-limited` | HTTP 429 |
| `:http-error` | Non-200 status (e.g. 404 for unknown ticker) |
| `:api-error` | Yahoo returned an error in the response body |
| `:no-data` | Empty result array from the API |
| `:parse-error` | JSON parsing failed |
| `:connection-error` | Network/socket exception |
| `:missing-price` | Valid response but price field absent |
| `:missing-data` | Valid response but historical data absent |
| `:missing-metadata` | Valid response but metadata fields absent |
| `:invalid-opts` | Invalid input parameters |
| `:timeout` | Future timed out during parallel fetch |
| `:execution-error` | Exception during parallel fetch task |
| `:interrupted` | Thread interrupted during parallel fetch |
| `:exception` | Unexpected exception |

---

## Dataset Integration (Optional)

The `clj-yfinance.dataset` namespace provides five conversion functions that bridge the library into the Clojure data science ecosystem (tablecloth, noj, datajure). tech.ml.dataset is kept as an optional dependency so the core library stays lightweight.

```clojure
;; Add to deps.edn
{:aliases {:dataset {:extra-deps {techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}

(require '[clj-yfinance.dataset :as yfd])

(yfd/historical->dataset "AAPL" :period "1mo")
; => #tech.v3.dataset [:timestamp :open :high :low :close :volume :adj-close]
;    column types: int64, float64 x5, int64

(yfd/prices->dataset (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
; => #tech.v3.dataset [:ticker :price]   (string, float64)

(yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")
; => single dataset with :ticker grouping column

(yfd/dividends-splits->dataset "AAPL" :period "10y")
; => {:dividends #tech.v3.dataset, :splits #tech.v3.dataset}

(yfd/info->dataset "AAPL")
; => single-row dataset with all metadata fields
```

Timestamps are stored as `:int64` (Unix epoch seconds). Convert with `java.time.Instant/ofEpochSecond`.

For datasets too large to fit in memory, [Clojask](https://github.com/clojure-finance/clojask) can process the data out-of-core. The simplest bridge is writing the dataset to CSV with `ds/write!` and reading it into Clojask with `ck/dataframe`.

```clojure
(require '[tech.v3.dataset :as ds])
(require '[clojask.dataframe :as ck])
(ds/write! (yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "5y") "data.csv")
(def ck-df (ck/dataframe "data.csv"))
```

---

## Experimental: Fundamentals & Company Data

The `clj-yfinance.experimental.fundamentals` namespace wraps Yahoo's authenticated `quoteSummary` endpoint. Authentication is fully automatic: on first use the library fetches a session cookie from `fc.yahoo.com` and a crumb token from Yahoo's API, caches the session, and refreshes it after one hour. No setup or API key required.

### Session Management

Session state is held in a singleton atom in `clj-yfinance.experimental.auth`:

```clojure
{:http-client    <HttpClient with CookieManager>
 :cookie-handler <CookieManager>
 :crumb          "Zcwe7K.UzyF"
 :created-at     1771295552000
 :status         :active}   ; or :uninitialized, :failed
```

On 401 responses the session is refreshed once automatically. Beyond that, errors surface as `:auth-failed`. You can inspect or force-refresh the session manually:

```clojure
(require '[clj-yfinance.experimental.auth :as auth])
(auth/session-info)     ; => {:status :active, :age-minutes 12.3, :crumb-present? true}
(auth/force-refresh!)   ; force a new session if needed
```

### Available Functions

| Function | Yahoo modules | Simple API returns |
|---|---|---|
| `fetch-fundamentals` / `*` | `financialData, defaultKeyStatistics` | Map with `:financialData` and `:defaultKeyStatistics` |
| `fetch-company-info` / `*` | `assetProfile` | `:assetProfile` map (sector, industry, employees, officers) |
| `fetch-analyst` / `*` | `earningsTrend, recommendationTrend, earningsHistory` | Map with all three module keys |
| `fetch-financials` / `*` | income/balance/cashflow (annual or quarterly) | Map with statement keys |
| `fetch-quotesummary*` | any (caller-specified) | `{:ok? true :data {...}}` |

`fetch-financials` accepts `:period :annual` (default) or `:period :quarterly`; an invalid value returns `:invalid-opts` without a network call.

Numeric values come back from Yahoo as `{:raw <number> :fmt <string>}` maps. Use `:raw` for calculations and `:fmt` for display. Yahoo partially restricts balance sheet and cash flow fields — only `:totalRevenue`, `:netIncome`, and `:endDate` are reliably available; the income statement is the most useful module.

### Additional Error Types (Experimental)

| Type | Cause |
|---|---|
| `:auth-failed` | Cookie/crumb refresh failed after retry |
| `:session-failed` | Session could not initialise |
| `:request-failed` | Network error during authenticated request |
| `:missing-data` | Response contained no usable `quoteSummary` result |
| `:invalid-opts` | Invalid option value (e.g. bad `:period`) |

---

## Experimental: Options Chains

The `clj-yfinance.experimental.options` namespace wraps Yahoo's v7 options endpoint, reusing the same cookie/crumb session as the fundamentals namespace.

```clojure
(require '[clj-yfinance.experimental.options :as yfo])

(yfo/fetch-options "AAPL")
; => {:underlying-symbol "AAPL"
;     :expiration-dates  [1771372800 1771977600 ...]
;     :strikes           [195.0 200.0 210.0 ... 335.0]
;     :expiration-date   1771372800
;     :quote             {:regularMarketPrice 255.78 ...}
;     :calls             [{:contractSymbol "AAPL260218C00210000"
;                          :strike 210.0 :bid 44.6 :ask 47.6
;                          :impliedVolatility 1.447 :openInterest 100
;                          :inTheMoney true ...} ...]
;     :puts              [...]}

;; Specific expiry
(yfo/fetch-options "AAPL" :expiration 1771977600)
```

Each contract map includes: `:contractSymbol` `:strike` `:bid` `:ask` `:lastPrice` `:impliedVolatility` `:openInterest` `:volume` `:inTheMoney` `:expiration` `:lastTradeDate` `:percentChange` `:change`. Detailed Greeks (delta, gamma, theta, vega) are not yet implemented.

---

## Tests

All tests are pure — no network calls. Parsing functions are tested with JSON fixtures; `reify` mocks stand in for HTTP responses in the experimental tests.

```bash
# Core (105 assertions)
clojure -M:test -e "(require 'clj-yfinance.core-test) (clj-yfinance.core-test/run-tests)"

# Auth (14 assertions)
clojure -M:test -e "(require 'clj-yfinance.experimental.auth-test) (clj-yfinance.experimental.auth-test/run-tests)"

# Fundamentals (61 assertions)
clojure -M:test -e "(require 'clj-yfinance.experimental.fundamentals-test) (clj-yfinance.experimental.fundamentals-test/run-tests)"

# Options (66 assertions)
clojure -M:test -e "(require 'clj-yfinance.experimental.options-test) (clj-yfinance.experimental.options-test/run-tests)"

# Dataset (requires tech.ml.dataset)
clojure -M:test:dataset -e "(require 'clj-yfinance.dataset-test) (clj-yfinance.dataset-test/run-tests)"
```

Test coverage includes: URL encoding, query string building, epoch time conversion, interval/range validation, input validation (periods, intervals, start/end ordering), time parameter construction, JSON parsing for all success and error branches, result envelope constructors, retry logic (retryable vs non-retryable errors), key normalisation (camelCase → kebab-case), future timeout handling, and dataset column types.

---

## Development

```bash
# Start REPL
clojure -M:nrepl          # connects on port 7888

# Quick smoke tests in the REPL
(require '[clj-yfinance.core :as yf])
(yf/fetch-price "AAPL")
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT"] :concurrency 2)
(take 5 (yf/fetch-historical "AAPL" :period "1mo"))
(yf/fetch-info "AAPL")

;; Error handling with verbose API
(let [results (yf/fetch-prices* ["AAPL" "GOOGL" "INVALID"])
      ok?     (fn [[_ r]] (:ok? r))]
  {:prices (into {} (map (fn [[t r]] [t (:data r)])  (filter ok? results)))
   :errors (into {} (map (fn [[t r]] [t (-> r :error :type)]) (remove ok? results)))})
; => {:prices {"AAPL" 261.05, "GOOGL" 337.28}
;     :errors {"INVALID" :http-error}}
```

---

## Publishing

Not yet published to Clojars. Clone the repo and use as a local dependency for now.

**GitHub repository:** https://github.com/clojure-finance/clj-yfinance

**Planned coordinates:** `com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.0"}`

**To publish:**
1. Generate `pom.xml` via `clojure -Spom`
2. Deploy with `lein deploy clojars` or `clj -T:build deploy`

---

## Limitations & Alternatives

The stable core uses Yahoo's public chart endpoint, which requires no authentication and has been stable for years. The experimental namespaces use Yahoo's authenticated endpoints with all the fragility that implies. Specific limitations worth knowing:

- **No built-in caching** — every call hits the network. Add `core.memoize` or similar at the application level.
- **No built-in rate limiting** — aggressive parallel use triggers 429 errors. Use the `:concurrency` option and implement retry logic via the verbose API.
- **Unofficial API** — Yahoo does not publicly document these endpoints. Check their Terms of Service before commercial use.
- **Restricted financial statement fields** — Yahoo limits balance sheet and cash flow data. For complete financial statements use Financial Modeling Prep or Alpha Vantage.

If you need production-grade data with stable APIs and SLAs:

- [Alpha Vantage](https://www.alphavantage.co/) — free tier, solid fundamentals and time series
- [Financial Modeling Prep](https://site.financialmodelingprep.com/) — comprehensive financials, statements, and screening
- [Massive](https://massive.com/) (formerly Polygon.io) — professional-grade market data and options

---

## License

Eclipse Public License 2.0 — see LICENSE.
