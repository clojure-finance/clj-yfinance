# clj-yfinance — Project Summary

## Overview

A pure Clojure library for fetching financial data from Yahoo Finance. Provides zero-dependency (aside from JSON parsing) access to current prices, historical OHLCV data, dividends/splits, and basic ticker information. No Python required, no API keys needed. Uses Java 11+ built-in `HttpClient` for HTTP requests.

**What works:** Price data, historical charts, dividends, splits, basic metadata  
**What doesn't:** Financial statements, options, comprehensive fundamentals (blocked by Yahoo's authentication)

**Target use case:** Clojure-based financial applications needing reliable price and historical data without the complexity of authentication or external dependencies.

---

## Environment

| Item | Value |
|---|---|
| Project type | deps.edn library (also provides project.clj for Leiningen) |
| Clojure | 1.11.1+ |
| Java | 11+ (requires `java.net.http.HttpClient`) |
| nREPL port | 7888 (dev only) |

---

## Key Files

| Path | Purpose |
|---|---|
| `src/clj_yfinance/core.clj` | Public API functions — 10 functions (5 simple + 5 verbose) |
| `src/clj_yfinance/http.clj` | HTTP client, retry logic, request/response handling |
| `src/clj_yfinance/parse.clj` | JSON parsing, URL encoding, data transformation utilities |
| `src/clj_yfinance/validation.clj` | Input validation and range checking |
| `test/clj_yfinance/core_test.clj` | Unit tests for pure functions (105 assertions, no network calls) |
| `deps.edn` | Deps.edn project file for Clojure CLI users |
| `project.clj` | Leiningen project file for Leiningen users |
| `README.md` | Library documentation with usage examples |
| `LICENSE` | Eclipse Public License 2.0 (EPL-2.0) |

---

## Dependencies

| Library | Version | Role |
|---|---|---|
| `cheshire/cheshire` | 6.1.0 | JSON parsing for Yahoo Finance API responses |

**Java dependencies (built-in):**
- `java.net.http.HttpClient` — HTTP requests
- `java.time.*` — Date/time handling for historical data ranges

---

## Public API

All functions are in the `clj-yfinance.core` namespace.

**Dual API Design:**
- **Simple API** (`fetch-price`, `fetch-prices`, `fetch-historical`, etc.): Returns data or `nil`/`[]` on failure — minimal code, easy to use
- **Verbose API** (`fetch-price*`, `fetch-prices*`, `fetch-historical*`, etc.): Returns structured result `{:ok? true :data ...}` or `{:ok? false :error {...}}` — for error handling, retries, and debugging

### Current Prices

```clojure
;; Simple API
(fetch-price ticker)
;; Returns: number or nil
;; Example: (fetch-price "AAPL") => 261.05

;; Verbose API - returns structured result
(fetch-price* ticker)
;; Returns: {:ok? true :data 261.05 :request {...}} or {:ok? false :error {...}}
;; Error types: :rate-limited, :http-error, :api-error, :parse-error,
;;              :connection-error, :missing-price, :invalid-opts
;; Error includes: :type, :ticker, :url, :status, :message
;; Success includes: :request {:ticker "AAPL" :query-params {...}}

;; Simple API - parallel fetch
(fetch-prices tickers)
(fetch-prices tickers :concurrency 8)
;; Returns: {ticker price} map (omits failures)
;; Example: (fetch-prices ["AAPL" "GOOGL"]) => {"AAPL" 261.05, "GOOGL" 337.28}
;; Uses bounded thread pool (default 8 concurrent requests)

;; Verbose API - parallel with error details
(fetch-prices* tickers)
(fetch-prices* tickers :concurrency 8)
;; Returns: {ticker result} map with success/failure for each
;; Example: (fetch-prices* ["AAPL" "INVALID"])
;; => {"AAPL" {:ok? true :data 261.05}
;;     "INVALID" {:ok? false :error {:type :http-error :status 404 ...}}}

;; Options:
;;   :concurrency - Max concurrent requests (default 8)
;;                  Lower values prevent rate limiting
;;                  Higher values increase throughput
```

### Historical Data

```clojure
;; Simple API
(fetch-historical ticker & opts)
;; Returns: vector of OHLCV maps or []
;; Options:
;;   :period    - "1d" "5d" "1mo" "3mo" "6mo" "1y" "2y" "5y" "10y" "ytd" "max"
;;   :interval  - "1m" "2m" "5m" "15m" "30m" "60m" "1h" "1d" "5d" "1wk" "1mo"
;;   :start     - Epoch seconds (integer) or Instant (overrides :period)
;;   :end       - Epoch seconds (integer) or Instant
;;   :adjusted  - Include adjusted close (default true)
;;   :prepost   - Include pre/post market (default false)

;; Example:
(fetch-historical "AAPL" :period "1mo")
;; => [{:timestamp 1704067200 :open 185.2 :high 186.1 :low 184.0
;;      :close 185.5 :volume 12345678 :adj-close 184.9} ...]

;; Verbose API
(fetch-historical* ticker & opts)
;; Returns: {:ok? true :data [...] :request {...} :warnings [...]} or {:ok? false :error {...}}
;; Error types: :http-error, :missing-data, :exception, :invalid-opts
;; Warnings returned as data (not printed) for invalid interval/range combinations
```

### Dividends & Splits

```clojure
;; Simple API
(fetch-dividends-splits ticker & opts)
;; Returns: {:dividends {...} :splits {...}}
;; Options:
;;   :period - Same as fetch-historical (default "5y")
;;   :start  - Epoch seconds (integer) or Instant
;;   :end    - Epoch seconds (integer) or Instant

;; Verbose API
(fetch-dividends-splits* ticker & opts)
;; Returns: {:ok? true :data {:dividends ... :splits ...}} or {:ok? false :error {...}}
```

### Ticker Info

```clojure
;; Simple API
(fetch-info ticker)
;; Returns: map or nil
;; Example: (fetch-info "AAPL")
;; => {:symbol "AAPL"
;;     :long-name "Apple Inc."
;;     :currency "USD"
;;     :exchange-name "NMS"
;;     :regular-market-price 261.05
;;     :regular-market-volume 92443408
;;     :fifty-two-week-high 288.62
;;     :fifty-two-week-low 169.21
;;     ...}

;; Provides basic ticker metadata including:
;; - Company identifiers (symbol, long-name, short-name)
;; - Current market data (price, volume, day high/low)
;; - Trading ranges (52-week high/low)
;; - Exchange info (currency, exchange name, timezone)
;; - Additional metadata (instrument type, first trade date)

;; Verbose API
(fetch-info* ticker)
;; Returns: {:ok? true :data {...}} or {:ok? false :error {...}}
;; Error types: :http-error, :missing-metadata, :exception

;; Note: Returns basic info from chart endpoint. For comprehensive
;; fundamentals (P/E ratio, market cap, EPS, sector, industry, description),
;; Yahoo's quoteSummary endpoint requires authentication which is blocked.
;; Use AlphaVantage or Financial Modeling Prep for these features.
```

---

## Architecture & Implementation Details

**Dual API Pattern:**
- **Simple API** (`fetch-price`, `fetch-prices`, `fetch-historical`, etc.): Thin wrappers that extract `:data` on success, return `nil`/`[]` on failure
  - Clean, concise interface for common cases
  - Zero side effects (warnings returned as data, not printed)
  - Minimal code required
- **Verbose API** (`fetch-price*`, `fetch-prices*`, `fetch-historical*`, etc.): Returns structured results `{:ok? bool :data/:error ... :request {...} :warnings [...]}`
  - Enables intelligent retry logic (distinguish rate limits vs invalid tickers)
  - Provides detailed error context (`:type`, `:ticker`, `:url`, `:status`, `:message`)
  - Supports partial success reporting (e.g., `fetch-prices*` returns per-ticker results)
  - Includes `:request` metadata with ticker and query params for debugging
  - Includes `:warnings` array for non-fatal issues (e.g., interval/range mismatches)

**Unified Pipeline:**
- **Single fetch-chart* function** - All public fetch functions (`fetch-price*`, `fetch-historical*`, `fetch-dividends-splits*`, `fetch-info*`) build on this unified pipeline
  - Standardized result envelope across all endpoints
  - Single place to add cross-cutting features (caching, instrumentation, etc.)
  - Consistent error handling and retry logic
  - Request metadata automatically attached to all results

**Input Validation:**
- **validate-opts** - Validates options before making network calls
  - Checks period against allowed values (1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd, max)
  - Checks interval against allowed values (1m, 2m, 5m, 15m, 30m, 60m, 90m, 1h, 1d, 5d, 1wk, 1mo, 3mo)
  - Validates `:end` requires `:start` (cannot use `:end` alone)
  - Validates either `:period` or `:start` must be provided
  - Validates start/end types (must be integer epoch seconds or java.time.Instant)
  - Validates start/end ordering (start must be <= end)
  - Returns `:invalid-opts` error immediately (< 1ms), saving network round-trip
  - Clear error messages show allowed values
- **interval-range-warnings** - Generates warnings for valid but potentially problematic interval/range combinations
  - Does NOT fail validation—warnings returned in `:warnings` key
  - Example: `1m` interval with `1mo` period (exceeds 7-day limit) generates warning but proceeds
  - Yahoo's API will ultimately reject truly invalid combinations
  - Allows flexibility for edge cases while providing guidance on limits

**Error types:**
- `:rate-limited` — HTTP 429, retry after delay
- `:http-error` — Non-200 status (e.g., 404 for invalid ticker)
- `:api-error` — Yahoo API returned error in response body
- `:no-data` — Empty result array from API (no data available)
- `:parse-error` — JSON parsing failed
- `:connection-error` — Network/socket exception
- `:missing-price`, `:missing-data`, `:missing-metadata` — Valid response but missing expected fields
- `:invalid-opts` — Invalid input parameters (period, interval, start/end range, or concurrency)
- `:execution-error` — Exception during parallel fetch task execution
- `:interrupted` — Thread interrupted during parallel fetch
- `:timeout` — Future timeout during parallel fetch (20 second limit)
- `:exception` — Unexpected exception during processing

**Separation of Concerns:**
- **Pure validation** - `interval-range-warnings` returns warning maps instead of printing, enabling custom handling
- **Centralized time params** - `chart-time-params` builds time query parameters, eliminating duplication
- **Testable parsing** - `parse-chart-body` is a pure function that can be tested with JSON fixtures
- **Type-stable HTTP** - `send-request*` and `try-fetch*` maintain consistent result types throughout pipeline

**URL Construction:**
- `->epoch` helper converts `Instant`/`long` to epoch seconds, centralizing timestamp logic
- `encode-path-segment` URL-encodes tickers (handles `^GSPC`, `BRK-B`, international symbols)
- `build-query` constructs query strings from maps with proper encoding, eliminating duplication
- **Conditional query string** - Only appends `?` to URLs when query parameters are non-empty (cleaner URLs)
- `chart-time-params` centralizes period/start/end parameter building for historical and dividend endpoints

**Key Normalization:**
- `camel->kebab` converts Yahoo's camelCase keys to idiomatic kebab-case
- `kebabize-keys` applies normalization to entire maps
- `key-exceptions` map handles special cases (e.g., "gmtoffset" → "gmt-offset")
- Replaces 20+ line manual key renaming with single function call
- Makes adding new fields trivial (no manual key mapping required)

**HTTP layer:**
- Delayed initialization of a single shared `HttpClient` instance with 10-second connect timeout
- **Per-request timeout** of 15 seconds prevents indefinite hangs on slow responses
- **Automatic retry with exponential backoff** for transient failures (rate limits, 5xx errors, connection errors)
  - Default: 3 attempts with 250ms base delay, plus jitter to avoid thundering herd
  - Retryable errors: `:rate-limited` (429), `:connection-error`, HTTP 5xx status codes
  - Non-retryable errors: 4xx status codes (except 429), API errors, parse errors
- Automatic fallback between `query1.finance.yahoo.com` and `query2.finance.yahoo.com`
- User-Agent header set to `Mozilla/5.0` to avoid blocks
- Type-stable pipeline: `send-request*` → `parse-response*` → `try-fetch*` always return `{:ok? ...}` maps
- Parse errors no longer prevent fallback to secondary URL (try/catch moved inside `parse-chart-body`)
- **No authentication** - uses only publicly accessible chart endpoints

**Data parsing:**
- Cheshire parses JSON with keywordized keys (`:meta`, `:chart`, etc.)
- **Result array validation** - Checks for nil/empty result arrays before processing
- **Empty result detection** - Returns `:no-data` error instead of `{:ok? true :data nil}` for empty responses
- OHLCV data extracted from nested `:indicators :quote` structure
- **Comprehensive OHLCV validation** - Validates timestamps, quotes object, and all required fields (open/high/low/close/volume) exist and are sequential
- Adjusted close from separate `:adjclose` array when `:adjusted true`
- Timestamps are Unix epoch seconds (convert with `java.time.Instant/ofEpochSecond`)
- All parsing errors return structured error responses with descriptive messages

**Parallelization:**
- `fetch-prices`/`fetch-prices*` use bounded thread pools (default 8 concurrent requests)
- **Concurrency validation** - Validates concurrency parameter is a positive integer before creating executor
- Configurable concurrency prevents overwhelming Yahoo's servers and triggering rate limits
- **Bounded future timeout** - 20-second timeout on `.get()` prevents indefinite hangs
- **Comprehensive exception handling** - Catches `TimeoutException`, `ExecutionException`, and `InterruptedException`
- **Timeout error handling** - Converts exceptions to structured errors (`:timeout`, `:execution-error`, `:interrupted`)
- **Robust executor shutdown** prevents thread leaks: graceful shutdown with 5-second wait, then aggressive `shutdownNow()` if needed
- Each ticker gets its own HTTP request to maximize throughput within concurrency bounds
- All errors handled gracefully with per-ticker structured error responses (no batch failures)

**Code Organization:**
- **4 namespace split** for separation of concerns:
  - `clj-yfinance.core` - Public API (simple + verbose functions)
  - `clj-yfinance.http` - HTTP layer with retry and timeout logic
  - `clj-yfinance.parse` - Pure parsing and transformation functions
  - `clj-yfinance.validation` - Input validation (strict) and warnings (permissive)

---

## Development Workflow

### Starting the REPL for testing

```bash
clojure -M:nrepl
```

Connects on **port 7888** for interactive development.

### Running Tests

The library includes unit tests for all pure functions:

```bash
# Run all tests
clojure -M:test -e "(require 'clj-yfinance.core-test) (clj-yfinance.core-test/run-tests)"
```

Tests cover:
- URL encoding and query string building
- Epoch time conversion
- Interval/range validation logic
- Input validation (periods, intervals, start/end ordering, :end requires :start)
- Time parameter construction
- JSON parsing with various fixtures
- Result constructors (ok/err)
- Retry logic (retryable vs non-retryable errors)
- Key normalization (camelCase to kebab-case conversion)
- Future timeout handling in parallel fetches

All tests are **pure** - no network calls required. They use fixtures to test parsing logic and validation without hitting Yahoo's API.

### Testing functions in the REPL

```clojure
(require '[clj-yfinance.core :as yf])

;; Quick price check
(yf/fetch-price "AAPL")

;; Ticker info
(yf/fetch-info "AAPL")

;; Control concurrency to avoid rate limiting
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT" "TSLA" "NVDA"] :concurrency 2)

;; Higher concurrency for faster throughput
(yf/fetch-prices large-ticker-list :concurrency 16)

;; Recent daily bars
(take 5 (yf/fetch-historical "AAPL" :period "1mo"))

;; Intraday data with extended hours
(yf/fetch-historical "TSLA"
                     :start (.minus (java.time.Instant/now)
                                    (java.time.Duration/ofDays 2))
                     :interval "5m"
                     :prepost true)

;; Using verbose API for error handling
(let [result (yf/fetch-price* "INVALID")]
  (if (:ok? result)
    (println "Price:" (:data result))
    (println "Error:" (:error result))))
;; => Error: {:type :http-error :status 404 :ticker "INVALID" ...}

;; Parallel fetch with detailed error reporting
(let [results (yf/fetch-prices* ["AAPL" "GOOGL" "INVALID"])
      failures (filter (fn [[_ r]] (not (:ok? r))) results)]
  {:success-count (count (filter (fn [[_ r]] (:ok? r)) results))
   :failure-details (into {} (map (fn [[t r]] [t (:error r)]) failures))})
;; => {:success-count 2
;;     :failure-details {"INVALID" {:type :http-error :status 404 ...}}}
```

### Testing Pure Functions (No Network Calls)

The library includes several pure functions that can be tested with fixtures:

```clojure
;; Test interval validation
(#'yf/interval-range-warnings {:interval "1m" :period "1mo"})
;; => [{:type :interval-too-wide :interval "1m" :max-days 7 :requested-days 30 ...}]

(#'yf/interval-range-warnings {:interval "1d" :period "1mo"})
;; => []

;; Test time params builder
(#'yf/chart-time-params {:period "1mo"})
;; => {:range "1mo"}

(#'yf/chart-time-params {:start 1704067200 :end 1706659200})
;; => {:period1 1704067200 :period2 1706659200}

;; Test JSON parsing with fixtures
(#'yf/parse-chart-body 
 "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100}}]}}")
;; => {:ok? true :data {...}}

(#'yf/parse-chart-body 
 "{\"chart\":{\"error\":{\"description\":\"No data found\"}}}")
;; => {:ok? false :error {:type :api-error :message "No data found"}}

(#'yf/parse-chart-body "invalid json")
;; => {:ok? false :error {:type :parse-error :message "..."}}
```

---

## What's NOT Included (And Why)

### Authentication-Required Features

The following features require Yahoo's quoteSummary API which implements authentication that blocks automated access:

- **Financial statements** (income, balance sheet, cash flow - annual/quarterly)
- **Options data** (chains, strikes, Greeks)
- **Comprehensive fundamentals** (P/E ratio, market cap, EPS, ROE, profit margins)
- **Company details** (sector, industry, description, employees)
- **Analyst data** (recommendations, price targets, estimates)
- **Insider/institutional holdings**

**Why not implemented:**
- Yahoo requires cookie/crumb authentication
- The crumb is no longer in predictable HTML locations
- Even Python's yfinance (actively maintained, large community) has ongoing issues with auth
- Implementation would be fragile and break frequently

**Recommended alternatives:**
- [AlphaVantage](https://www.alphavantage.co/) - Free tier, good fundamentals
- [Financial Modeling Prep](https://financialmodelingprep.com/) - Comprehensive financials
- [Polygon.io](https://polygon.io/) - Professional-grade data
- [IEX Cloud](https://iexcloud.io/) - Real-time & reference data

---

## Extension Points

**Retry with exponential backoff:** Use `fetch-*` functions to implement retry logic that respects `:rate-limited` errors:
```clojure
(defn fetch-with-retry [ticker max-retries]
  (loop [attempt 0]
    (let [result (yf/fetch-price* ticker)]
      (cond
        (:ok? result) (:data result)
        (and (= :rate-limited (-> result :error :type))
             (< attempt max-retries))
        (do (Thread/sleep (* 1000 (Math/pow 2 attempt)))
            (recur (inc attempt)))
        :else nil))))
```

**Caching:** Integrate `clojure.core.cache` or `core.memoize` to avoid redundant fetches during development.

**Data transformation:** Add utility functions to convert timestamps to `LocalDate`, compute returns, align time series, etc.

**Async fetching:** Replace `pmap` with core.async channels or manifold for more sophisticated concurrent workflows with bounded parallelism.

**Dataset integration (planned for v0.2.0+):** Integration with tablecloth/tech.ml.dataset for data analysis workflows. Planned features include:
```clojure
;; Convert historical data to dataset
(require '[clj-yfinance.dataset :as yfd])

(-> (yf/fetch-historical "AAPL" :period "1mo")
    (yfd/historical->dataset :ticker "AAPL"))

;; Multi-ticker analysis
(yfd/historical-multi->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")

;; Price comparison dataset
(-> (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"])
    (yfd/prices->dataset))
```

This would enable seamless integration with the Clojure data science ecosystem (tablecloth, noj, scicloj) while keeping the core library lightweight. The dataset namespace would be provided as an optional dependency to avoid forcing tech.ml.dataset on users who don't need it.

---

## Recent Improvements (February 2026)

The library underwent comprehensive code review and hardening with the following critical fixes:

### Robustness Improvements
1. **Parallel fetch exception handling** - `fetch-prices*` now catches all exception types (`ExecutionException`, `InterruptedException`, `TimeoutException`) and converts them to structured per-ticker errors instead of crashing the entire batch
2. **Historical data validation** - Added comprehensive checks for missing timestamps, missing quotes object, and missing/invalid OHLCV fields (open/high/low/close/volume), preventing silent `[]` returns
3. **Concurrency parameter validation** - Validates concurrency is a positive integer before creating thread pool, preventing `IllegalArgumentException` with clear structured error message
4. **Empty result detection** - `parse-chart-body` now validates result arrays and returns `:no-data` error instead of `{:ok? true :data nil}` for empty responses

### Code Quality Improvements
5. **URL construction** - Fixed to only append `?` when query parameters exist (cleaner, more correct URLs)
6. **Distribution hygiene** - Removed `.cpcache/` build artifacts and `VERIFICATION.md` file with inconsistencies
7. **Documentation accuracy** - Corrected group ID from `io.github.clojure-finance` to `com.github.clojure-finance` to match existing clojure-finance organization pattern on Clojars
8. **Publication status** - Clearly documented that library is not yet published with instructions for current usage

### Error Type Additions
- `:no-data` - Empty result array from API
- `:execution-error` - Exception during parallel fetch execution
- `:interrupted` - Thread interrupted during parallel fetch
- `:timeout` - Future timeout in parallel fetch

All improvements enhance the robustness and error reporting of the library without changing the simple API's behavior.

---

## Publishing (Not Yet Released)

**Current Status:** This library is not yet published to Clojars.

**Planned Coordinates:** `com.github.clojure-finance/clj-yfinance` (matching the existing clojure-finance organization pattern on Clojars)

**GitHub Repository:** https://github.com/clojure-finance/clj-yfinance (to be created)

**To publish:**

1. Create GitHub repo at `clojure-finance/clj-yfinance`
2. Generate `pom.xml` via `clojure -Spom`
3. Deploy to Clojars with `lein deploy clojars` or `clj -T:build deploy`
4. Update README and PROJECT_SUMMARY with actual published coordinates

**When published, installation will be:**

```clojure
;; deps.edn
com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.0"}

;; project.clj
[com.github.clojure-finance/clj-yfinance "0.1.0"]
```

---

## Limitations & Disclaimers

- **No authenticated endpoints** — Financial statements, options, and comprehensive fundamentals unavailable
- **Unofficial API** — Yahoo Finance chart endpoint (v8) is not officially documented but has been stable for years
- **No built-in rate limiting** — Aggressive use may trigger 429 responses; implement retry logic using `fetch-*` functions
- **No caching** — Every call hits the network; cache at application level for performance
- **Educational/personal use** — Check Yahoo's ToS before using in commercial applications

## License

Eclipse Public License 2.0 (EPL-2.0) - See LICENSE file for full text.
