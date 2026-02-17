# clj-yfinance

Pure Clojure client for Yahoo Finance. No Python, no API key required.

## What's Included

✅ **Current prices** - Single ticker and parallel multi-ticker fetching  
✅ **Historical data** - OHLCV with customizable periods and intervals  
✅ **Dividends & splits** - Corporate action history  
✅ **Ticker info** - Basic company metadata (name, exchange, price ranges)

⚠️ **Experimental:** Company fundamentals (P/E, market cap, EPS, margins, analyst targets) - uses Yahoo's authenticated endpoints which may break. See [Experimental Features](#experimental-features-fundamentals) below.

❌ **Not included:** Financial statements, options data, comprehensive analyst estimates - not available without authentication that Yahoo actively blocks.

## Installation

**Note:** This library is not yet published to Clojars. To use it, clone the repository:

```bash
git clone https://github.com/clojure-finance/clj-yfinance.git
cd clj-yfinance
```

Then add it as a local dependency or install locally. Once published, it will be available as:

```clojure
;; deps.edn (when published)
com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.0"}

;; project.clj (when published)
[com.github.clojure-finance/clj-yfinance "0.1.0"]
```

**Requirements:** JDK 11+ (uses `java.net.http.HttpClient`)

## Usage

```clojure
(require '[clj-yfinance.core :as yf])
(import '[java.time Instant Duration])

;; Current price
(yf/fetch-price "AAPL")
;; => 261.05

;; Multiple tickers (fetched in parallel)
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT" "0005.HK"])
;; => {"AAPL" 261.05, "GOOGL" 337.28, "MSFT" 428.04, "0005.HK" 59.85}

;; Historical OHLCV data
(yf/fetch-historical "AAPL" :period "1mo")
;; => [{:timestamp 1704067200, :open 185.2, :high 186.1, :low 184.0,
;;      :close 185.5, :volume 12345678, :adj-close 184.9} ...]

;; Custom date range with intraday bars
(yf/fetch-historical "TSLA"
                     :start (.minus (Instant/now) (Duration/ofDays 7))
                     :interval "1h"
                     :prepost true)  ; include pre/post market

;; Dividends and splits
(yf/fetch-dividends-splits "AAPL" :period "10y")
;; => {:dividends {1699574400 {"amount" 0.24, "date" 1699574400}, ...}
;;     :splits {1598832000 {"numerator" 4, "denominator" 1, ...}, ...}}

;; Ticker info
(yf/fetch-info "AAPL")
;; => {:symbol "AAPL", :long-name "Apple Inc.", :currency "USD",
;;     :exchange-name "NMS", :regular-market-price 261.05,
;;     :fifty-two-week-high 288.62, :fifty-two-week-low 169.21, ...}
```

### Error Handling with Verbose API

```clojure
;; Get detailed error information
(yf/fetch-price* "INVALIDTICKER")
;; => {:ok? false
;;     :error {:type :http-error
;;             :status 404
;;             :ticker "INVALIDTICKER"
;;             :url "https://query1.finance.yahoo.com/..."}}

;; Handle rate limiting
(let [result (yf/fetch-price* "AAPL")]
  (if (:ok? result)
    (println "Price:" (:data result))
    (when (= :rate-limited (-> result :error :type))
      (println "Rate limited, retry later"))))

;; Batch fetch with partial success
(let [results (yf/fetch-prices* ["AAPL" "GOOGL" "INVALID"])
      successes (filter (fn [[_ r]] (:ok? r)) results)
      failures (filter (fn [[_ r]] (not (:ok? r))) results)]
  {:prices (into {} (map (fn [[t r]] [t (:data r)]) successes))
   :errors (into {} (map (fn [[t r]] [t (:error r)]) failures))})
;; => {:prices {"AAPL" 270.01 "GOOGL" 343.69}
;;     :errors {"INVALID" {:type :http-error :status 404 ...}}}
```

## Dataset Integration (Optional)

For integration with Clojure's data science ecosystem (tech.ml.dataset, tablecloth, noj), add to your `deps.edn`:

```clojure
{:deps {techascent/tech.ml.dataset {:mvn/version "7.032"}}}
```

Then use the dataset namespace:

```clojure
(require '[clj-yfinance.dataset :as yfd])

;; Convert historical data to tech.v3.dataset
(yfd/historical->dataset "AAPL" :period "1mo")
;; => #tech.v3.dataset with typed columns [:timestamp :open :high :low :close :volume :adj-close]

;; Convert price map to dataset
(def prices (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
(yfd/prices->dataset prices)
;; => #tech.v3.dataset with columns [:ticker :price]

;; Multi-ticker analysis in single dataset
(yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")
;; => #tech.v3.dataset with :ticker column for grouping

;; Dividends and splits as datasets
(yfd/dividends-splits->dataset "AAPL" :period "10y")
;; => {:dividends #tech.v3.dataset, :splits #tech.v3.dataset}

;; Ticker info as single-row dataset
(yfd/info->dataset "AAPL")
```

### Works Automatically With:

**tablecloth** (ergonomic data manipulation):
```clojure
(require '[tablecloth.api :as tc])

(-> (yfd/historical->dataset "AAPL" :period "1mo")
    (tc/add-column :returns (fn [ds] 
                              (let [closes (ds :close)]
                                (map / (rest closes) closes))))
    (tc/select-columns [:timestamp :close :returns]))
```

**noj** (notebook environment with visualization):
```clojure
(require '[scicloj.noj.v1.api :as noj])

;; Interactive visualization in notebooks
(noj/view (yfd/historical->dataset "AAPL" :period "1mo"))
```

**datajure** (R-like tidyverse syntax):
```clojure
(require '[datajure.dplyr :as dplyr])

(-> (yfd/historical->dataset "AAPL" :period "1mo")
    (dplyr/mutate :returns '(/ close (lag close)))
    (dplyr/filter '(> returns 0.01)))
```

### Converting to Clojask (Parallel/Out-of-Core Processing)

**Note:** Clojask uses a different paradigm (lazy parallel processing for huge datasets). It's not directly supported, but you can convert as needed:

```clojure
(require '[clojask.api :as ck])

;; Option 1: Convert from raw data (maps)
(def raw-data (yf/fetch-historical "AAPL" :period "10y"))
(def dk (ck/dataframe raw-data))

;; Option 2: Convert from tech.v3.dataset
(require '[tablecloth.api :as tc])
(def ds (yfd/historical->dataset "AAPL" :period "10y"))
(def dk (ck/dataframe (tc/rows ds :as-maps)))

;; Now use Clojask's parallel operations
(ck/operate dk ...)
(ck/compute dk)
```

Clojask is designed for datasets too large for memory (100GB+). For typical financial data from Yahoo Finance (even 10 years of minute bars), tech.v3.dataset's in-memory approach is simpler and faster.

### Column Types

All datasets use proper type specifications for performance:

- **Timestamps**: `:int64` (Unix epoch seconds)
- **Prices** (open/high/low/close/adj-close): `:float64`
- **Volume**: `:int64`
- **Tickers**: `:string`

Convert timestamps to dates as needed:
```clojure
(require '[tech.v3.datatype.datetime :as dtype-dt])
(dtype-dt/instant->zoned-date-time 
  (java.time.Instant/ofEpochSecond timestamp) 
  (java.time.ZoneId/of "America/New_York"))
```

## Experimental Features: Fundamentals

> ⚠️ **EXPERIMENTAL** — This feature may break at any time. Yahoo can change or block authentication without notice. For production applications, use a stable alternative (see below).

The `clj-yfinance.experimental.fundamentals` namespace provides access to company fundamentals via Yahoo's authenticated `quoteSummary` endpoint. Authentication is handled automatically using a cookie/crumb session (no API key required).

### What's Available

- **Valuation:** P/E ratio (trailing & forward), price-to-book, enterprise value, market cap
- **Profitability:** Profit margins, operating margins, return on equity, return on assets
- **Financial health:** Total revenue, EBITDA, debt-to-equity, total cash per share
- **Analyst data:** Recommendation (buy/hold/sell), mean price target, number of analysts
- **Key statistics:** Beta, 52-week change, shares outstanding, float shares, institutional holdings

### Usage

```clojure
(require '[clj-yfinance.experimental.fundamentals :as yff])

;; Simple API - returns data map or nil on failure
(yff/fetch-fundamentals "AAPL")
;; => {:marketCap {:raw 4034211348480 :fmt "4.03T"}
;;     :currentPrice {:raw 255.78 :fmt "255.78"}
;;     :recommendationKey "buy"
;;     :trailingPE {:raw 32.4 :fmt "32.40"}
;;     :forwardPE {:raw 28.1 :fmt "28.10"}
;;     :profitMargins {:raw 0.2531 :fmt "25.31%"}
;;     :targetMeanPrice {:raw 265.00 :fmt "265.00"}
;;     ...}  ; 100+ fields total

;; Verbose API - returns {:ok? true :data ...} or {:ok? false :error ...}
(yff/fetch-fundamentals* "AAPL")
;; => {:ok? true
;;     :data {:marketCap {...} :currentPrice {...} ...}}

;; Error handling
(let [result (yff/fetch-fundamentals* "INVALID")]
  (when-not (:ok? result)
    (println "Error:" (-> result :error :type))
    (println "Suggestion:" (-> result :error :suggestion))))
;; => Error: :http-error
;; => Suggestion: Verify the ticker symbol is correct...

;; Access specific fields
(let [data (yff/fetch-fundamentals "MSFT")]
  {:pe-ratio     (-> data :trailingPE :raw)
   :market-cap   (-> data :marketCap :fmt)
   :rating       (:recommendationKey data)
   :target-price (-> data :targetMeanPrice :raw)})
;; => {:pe-ratio 32.4, :market-cap "3.21T", :rating "buy", :target-price 510.00}
```

### Note on Data Format

Yahoo returns numeric values as `{:raw <number> :fmt <string>}` maps. Use `:raw` for calculations and `:fmt` for display. Some fields (like `:recommendationKey`) are plain strings.

### Session Management

Authentication is handled automatically:
- Sessions are initialized on first use (cookie + crumb from Yahoo)
- Sessions auto-refresh after 1 hour
- On 401 errors, the session refreshes once and retries automatically
- A singleton session is reused across all requests (no repeated auth)

```clojure
;; Check session status (for debugging)
(require '[clj-yfinance.experimental.auth :as auth])
(auth/session-info)
;; => {:status :active, :age-minutes 12.3, :crumb-present? true}

;; Force a session refresh if needed
(auth/force-refresh!)
```

### Error Types

| Error | Cause | Suggestion |
|-------|-------|------------|
| `:auth-failed` | Cookie/crumb refresh failed | Wait and retry; Yahoo may be blocking |
| `:session-failed` | Session could not initialize | Check network; try again later |
| `:api-error` | Yahoo returned error (e.g., bad ticker) | Verify ticker is valid |
| `:http-error` | Non-200 status (404, etc.) | Check ticker; some may lack fundamentals |
| `:rate-limited` | HTTP 429 - too many requests | Wait several minutes before retrying |
| `:parse-error` | JSON parsing failed | Yahoo may have changed response format |
| `:missing-data` | No result in response | Ticker may not have fundamentals available |
| `:request-failed` | Network/connection error | Check network connectivity |

### Stable Alternatives

If you need production-grade fundamentals data:
- [AlphaVantage](https://www.alphavantage.co/) — Free tier, good fundamentals API
- [Financial Modeling Prep](https://financialmodelingprep.com/) — Comprehensive financials and screening
- [Polygon.io](https://polygon.io/) — Professional-grade market data

---

## API Reference

**Dual API Design:**
- **Simple API** (`fetch-price`, `fetch-prices`, `fetch-historical`, etc.): Returns data or `nil`/`[]` on failure — easy to use, minimal code
- **Verbose API** (`fetch-price*`, `fetch-prices*`, `fetch-historical*`, etc.): Returns structured `{:ok? true :data ...}` or `{:ok? false :error {...}}` — for error handling and retries

### `fetch-price` / `fetch-price*`
```clojure
(fetch-price ticker)
```
Returns current market price as a number, or `nil` on failure.

```clojure
(fetch-price* ticker)
```
Returns `{:ok? true :data 261.05}` or `{:ok? false :error {...}}` with detailed error information.

**Error types:** `:rate-limited`, `:http-error`, `:api-error`, `:parse-error`, `:connection-error`, `:missing-price`, `:invalid-opts`

### `fetch-prices` / `fetch-prices*`
```clojure
(fetch-prices tickers)
(fetch-prices tickers :concurrency 8)
```
Returns `{ticker price}` map, omitting failures. Fetches in parallel with bounded concurrency (default 8 concurrent requests).

```clojure
(fetch-prices* tickers)
(fetch-prices* tickers :concurrency 8)
```
Returns `{ticker result}` map with success/failure status for each ticker, enabling partial success handling.

**Options:**
- `:concurrency` - Maximum number of concurrent requests (default 8). Lower values reduce load on Yahoo's servers and prevent rate limiting; higher values increase throughput.

### `fetch-historical` / `fetch-historical*`
```clojure
(fetch-historical ticker & opts)
```
Returns vector of OHLCV maps, or `[]` on failure.

```clojure
(fetch-historical* ticker & opts)
```
Returns `{:ok? true :data [...]}` or `{:ok? false :error {...}}` with detailed error information.

| Option | Default | Description |
|--------|---------|-------------|
| `:period` | `"1y"` | `1d` `5d` `1mo` `3mo` `6mo` `1y` `2y` `5y` `10y` `ytd` `max` |
| `:interval` | `"1d"` | `1m` `2m` `5m` `15m` `30m` `60m` `90m` `1h` `1d` `5d` `1wk` `1mo` `3mo` |
| `:start` | — | Epoch seconds (integer) or `Instant` (overrides `:period`) |
| `:end` | now | Epoch seconds (integer) or `Instant` |
| `:adjusted` | `true` | Include adjusted close |
| `:prepost` | `false` | Include pre/post market data |

**Note:** Invalid individual values (period/interval not in allowed sets, `:end` without `:start`, `start > end`) are rejected immediately with `:invalid-opts` error. Valid but incompatible combinations (e.g., `1m` interval with `1mo` period exceeding 7-day limit) generate warnings in the `:warnings` key but don't fail validation—Yahoo's API will reject truly invalid requests. This allows flexibility for edge cases while providing guidance on interval range limits.

### `fetch-dividends-splits` / `fetch-dividends-splits*`
```clojure
(fetch-dividends-splits ticker & opts)
```
Returns `{:dividends {...} :splits {...}}` or empty maps on failure.

```clojure
(fetch-dividends-splits* ticker & opts)
```
Returns `{:ok? true :data {:dividends ... :splits ...}}` or `{:ok? false :error {...}}`.

| Option | Default | Description |
|--------|---------|-------------|
| `:period` | `"5y"` | Same as `fetch-historical` |
| `:start` | — | Epoch seconds (integer) or `Instant` |
| `:end` | now | Epoch seconds (integer) or `Instant` |

### `fetch-info` / `fetch-info*`
```clojure
(fetch-info ticker)
```
Returns basic ticker information and metadata as a map, or `nil` on failure.

```clojure
(fetch-info* ticker)
```
Returns `{:ok? true :data {...}}` or `{:ok? false :error {...}}` with detailed error information.

Includes:
- Company identifiers: `:symbol`, `:long-name`, `:short-name`
- Market data: `:regular-market-price`, `:regular-market-volume`, `:chart-previous-close`
- Trading ranges: `:regular-market-day-high`, `:regular-market-day-low`, `:fifty-two-week-high`, `:fifty-two-week-low`
- Exchange info: `:currency`, `:exchange-name`, `:full-exchange-name`, `:timezone`, `:gmt-offset`
- Additional: `:instrument-type`, `:first-trade-date`, `:has-pre-post-market-data`

**Note:** This returns basic metadata from the chart endpoint. For fundamentals (P/E ratio, market cap, EPS, margins, analyst targets), see the experimental `clj-yfinance.experimental.fundamentals` namespace. Financial statements, options, and detailed analyst estimates use the same authentication mechanism and could be added to the experimental namespace in the future.

## Features

- **Dual API design** - Simple API for ease of use, verbose API for error handling and retries
- **Input validation** - Invalid parameters rejected before network calls (< 1ms) with clear error messages
- **Unified pipeline** - Standardized result envelope with `:request` metadata and `:warnings` array across all endpoints
- **Bounded concurrency** - Configurable thread pool for parallel fetching (default 8), prevents overwhelming Yahoo's servers
- **Automatic retry with backoff** - Handles transient failures (rate limits, 5xx errors, connection issues) with exponential backoff
- **Request timeouts** - 15-second per-request timeout prevents indefinite hangs
- **Zero external dependencies** beyond Cheshire (JSON parsing)
- **Automatic fallback** between `query1` and `query2` Yahoo endpoints
- **Structured error handling** - 9 error types with detailed context (`:type`, `:ticker`, `:url`, `:status`, `:message`)
- **URL encoding** - Handles special characters in tickers (e.g., `^GSPC`, `BRK-B`)
- **Automatic key normalization** - Yahoo's camelCase keys converted to idiomatic kebab-case
- **Pure, testable functions** - 90 unit tests covering all validation and parsing logic

## Development

### Running Tests

```bash
# Core library tests (no network calls)
clojure -M:test -e "(require 'clj-yfinance.core-test) (clj-yfinance.core-test/run-tests)"

# Experimental auth tests (no network calls)
clojure -M:test -e "(require 'clj-yfinance.experimental.auth-test) (clj-yfinance.experimental.auth-test/run-tests)"

# Dataset tests (requires tech.ml.dataset)
clojure -M:test:dataset -e "(require 'clj-yfinance.dataset-test) (clj-yfinance.dataset-test/run-tests)"

# Run all tests
clojure -M:test:dataset -e "(require 'clj-yfinance.core-test 'clj-yfinance.dataset-test) (clojure.test/run-all-tests #\"clj-yfinance.*-test\")"
```

All tests are pure functions with no network calls - they test URL encoding, query building, validation logic, JSON parsing, and dataset conversions using fixtures.

## Limitations

- **Experimental fundamentals**: Cookie/crumb auth works but may break when Yahoo changes authentication — use with caution in production
- **Experimental namespace not yet expanded**: Financial statements, options chains, and detailed analyst estimates are not yet implemented - the same cookie/crumb auth used for fundamentals should support these in future
- **No built-in rate limiting**: Yahoo may throttle aggressive use
- **No caching**: Add your own with `core.cache` if needed
- **Unofficial API**: Yahoo could change it, though the chart endpoint (v8) has been stable for years

## Alternatives for Missing Features

For financial statements, options, comprehensive fundamentals:
- [AlphaVantage](https://www.alphavantage.co/) - Free tier available, good fundamentals API
- [Financial Modeling Prep](https://financialmodelingprep.com/) - Comprehensive financials, screening
- [Polygon.io](https://polygon.io/) - Professional-grade market data
- [IEX Cloud](https://iexcloud.io/) - Real-time and reference data

## License

Eclipse Public License 2.0
