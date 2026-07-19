# clj-yfinance

[![Clojars Project](https://img.shields.io/clojars/v/com.github.clojure-finance/clj-yfinance.svg)](https://clojars.org/com.github.clojure-finance/clj-yfinance)
[![CI](https://github.com/clojure-finance/clj-yfinance/actions/workflows/ci.yml/badge.svg)](https://github.com/clojure-finance/clj-yfinance/actions/workflows/ci.yml)
[![cljdoc badge](https://cljdoc.org/badge/com.github.clojure-finance/clj-yfinance?bustCache=1)](https://cljdoc.org/d/com.github.clojure-finance/clj-yfinance)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A pure Clojure client for Yahoo Finance — prices, historical OHLCV, dividends, splits, fundamentals, financial statements, analyst estimates, options chains, and more.

No Python bridge. No API key. No external HTTP dependencies. Just Clojure and the `java.net.http.HttpClient` that ships with JDK 11+.

## Why clj-yfinance?

- **Zero-config data access** — fetch live prices, historical bars, and company fundamentals with a single function call.
- **Parallel multi-ticker fetching** — bounded-concurrency batch requests with per-ticker error handling.
- **Two-tier API** — simple functions return data directly; verbose (`*`) variants return `{:ok? :data :error}` maps for production error handling.
- **First-class data science integration** — built-in support for [tech.ml.dataset](https://github.com/techascent/tech.ml.dataset), [tablecloth](https://github.com/scicloj/tablecloth), [Noj](https://github.com/scicloj/noj), [Kindly](https://github.com/scicloj/kindly)/[Clay](https://github.com/scicloj/clay), Parquet, and DuckDB.
- **Minimal footprint** — one runtime dependency ([charred](https://github.com/cnuernber/charred) for JSON parsing). Everything else is optional.

## At a Glance

| Capability | Namespace | Status |
|---|---|---|
| Current prices (single & batch) | `clj-yfinance.core` | **Stable** |
| Historical OHLCV bars | `clj-yfinance.core` | **Stable** |
| Dividends & stock splits | `clj-yfinance.core` | **Stable** |
| Ticker metadata | `clj-yfinance.core` | **Stable** |
| Company fundamentals & profile | `clj-yfinance.experimental.fundamentals` | Experimental |
| Analyst estimates & recommendations | `clj-yfinance.experimental.fundamentals` | Experimental |
| Income/balance/cash-flow statements | `clj-yfinance.experimental.fundamentals` | Experimental |
| Earnings calendar & dividend dates | `clj-yfinance.experimental.fundamentals` | Experimental |
| Options chains | `clj-yfinance.experimental.options` | Experimental |
| Dataset conversion (tech.ml.dataset) | `clj-yfinance.dataset` | **Stable** |
| Kindly-tagged datasets (Clay/Portal) | `clj-yfinance.kindly` | **Stable** |
| Parquet read/write | `clj-yfinance.parquet` | **Stable** |
| DuckDB SQL queries | `clj-yfinance.duckdb` | **Stable** |

**Stable** features use Yahoo's public chart endpoint, which has been reliable for years.  
**Experimental** features use Yahoo's authenticated `quoteSummary` / options endpoints — they work today but may break if Yahoo changes their authentication mechanism.

## Installation

```clojure
;; deps.edn
com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.7"}

;; project.clj
[com.github.clojure-finance/clj-yfinance "0.1.7"]
```

Requires **JDK 11+**. The only runtime dependency is [charred](https://github.com/cnuernber/charred) for JSON parsing. All other integrations (datasets, Parquet, DuckDB, Kindly) are opt-in via aliases.

---

## Quick Start

```clojure
(require '[clj-yfinance.core :as yf])

;; Current price
(yf/fetch-price "AAPL")
;; => 261.05

;; Batch prices in parallel
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT" "0005.HK"])
;; => {"AAPL" 261.05, "GOOGL" 337.28, "MSFT" 428.04, "0005.HK" 59.85}

;; Daily bars for the past year
(yf/fetch-historical "AAPL" :period "1y")
;; => [{:timestamp 1704067200, :open 185.2, :high 186.1, :low 184.0,
;;      :close 185.5, :volume 12345678, :adj-close 184.9} ...]

;; As a typed dataset
(require '[clj-yfinance.dataset :as yfd])
(yfd/historical->dataset "AAPL" :period "1mo")
;; => #tech.v3.dataset [:timestamp :open :high :low :close :volume :adj-close]
```

---

## Core API

All stable functions live in `clj-yfinance.core`. Every function has two flavours:

- **Simple** (`fetch-price`, `fetch-historical`, …) — returns data directly, or `nil`/`[]` on failure.
- **Verbose** (`fetch-price*`, `fetch-historical*`, …) — returns `{:ok? true :data …}` or `{:ok? false :error {…}}` for structured error handling.

### Prices

```clojure
;; Single ticker
(yf/fetch-price "AAPL")
;; => 261.05

;; Multiple tickers with bounded concurrency (default 8 threads)
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT" "0005.HK"])
;; => {"AAPL" 261.05, "GOOGL" 337.28, "MSFT" 428.04, "0005.HK" 59.85}

;; Lower concurrency to avoid rate limiting
(yf/fetch-prices large-ticker-list :concurrency 2)

;; Verbose — per-ticker success/failure detail
(yf/fetch-prices* ["AAPL" "INVALID"])
;; => {"AAPL"    {:ok? true  :data 261.05}
;;     "INVALID" {:ok? false :error {:type :http-error :status 404 ...}}}
```

### Historical Data

```clojure
(import '[java.time Instant Duration])

;; Daily bars for the past month
(yf/fetch-historical "AAPL" :period "1mo")

;; Intraday with a custom date range
(yf/fetch-historical "TSLA"
                     :start (.minus (Instant/now) (Duration/ofDays 7))
                     :interval "1h"
                     :prepost true)   ; include pre/post market
```

| Parameter | Values |
|---|---|
| `:period` | `1d` `5d` `1mo` `3mo` `6mo` `1y` `2y` `5y` `10y` `ytd` `max` |
| `:interval` | `1m` `2m` `5m` `15m` `30m` `60m` `90m` `1h` `1d` `5d` `1wk` `1mo` `3mo` |
| `:start` / `:end` | epoch seconds (integer) or `java.time.Instant`; `:start` overrides `:period` |
| `:adjusted` | include adjusted close (default `true`) |
| `:auto-adjust` | back-adjust `:open`/`:high`/`:low`/`:close` by the adjclose/close ratio, like python-yfinance's `auto_adjust=True` (default `false`) |
| `:prepost` | include pre/post market data (default `false`) |

Invalid parameter combinations are caught before any network call and returned as `:invalid-opts` errors. Technically valid but potentially problematic combinations (e.g. `1m` interval over a 30-day range) produce a warning in the `:warnings` key of the verbose response.

### Dividends & Splits

```clojure
(yf/fetch-dividends-splits "AAPL" :period "10y")
;; => {:dividends {1699574400 {:amount 0.24 :date 1699574400} ...}
;;     :splits    {1598832000 {:numerator 4 :denominator 1 ...} ...}}
```

Accepts the same `:period`, `:start`, `:end` options as `fetch-historical`. Default period is `"5y"`.

#### Split factors

Yahoo back-adjusts all historical quotes for splits, so share counts recorded before a split no longer match the price series. `cumulative-split-factor` (pure) and `fetch-split-factor` (fetching) compute the factor by which such a position's share count must be multiplied (and its per-share cost basis divided) to be consistent with today's quotes:

```clojure
;; One NVDA share bought before the 2024-06-10 10:1 split is 10 shares today
(yf/fetch-split-factor "NVDA" (Instant/parse "2024-06-01T23:59:59Z"))
;; => 10.0

;; Pure variant, reusing already-fetched events
(let [{:keys [splits]} (yf/fetch-dividends-splits "NVDA" :period "max")]
  (yf/cumulative-split-factor splits (Instant/parse "2024-06-01T23:59:59Z")))
;; => 10.0
```

Splits dated exactly at the `since` timestamp are excluded (trades on the effective date are already in post-split units); with date-granularity trade data, pass the end of the trade day.

### Ticker Info

```clojure
(yf/fetch-info "AAPL")
;; => {:symbol "AAPL"
;;     :long-name "Apple Inc."
;;     :currency "USD"
;;     :exchange-name "NMS"
;;     :regular-market-price 261.05
;;     :regular-market-volume 92443408
;;     :fifty-two-week-high 288.62
;;     :fifty-two-week-low 169.21
;;     :timezone "America/New_York"
;;     ...}
```

Returns identifiers, current price, day/52-week ranges, and exchange info from Yahoo's public chart endpoint. For richer company data (sector, description, officers, P/E), see `fetch-company-info` in the experimental namespace.

### Error Handling

The verbose API provides structured errors with enough context for intelligent retry logic:

```clojure
(let [result (yf/fetch-price* "AAPL")]
  (if (:ok? result)
    (:data result)
    (case (-> result :error :type)
      :rate-limited  (do (Thread/sleep 5000) (yf/fetch-price "AAPL"))
      :http-error    (println "Bad ticker or endpoint:" (-> result :error :status))
      :parse-error   (println "Yahoo changed their format")
      nil)))
```

**Error types:** `:rate-limited` · `:http-error` · `:api-error` · `:parse-error` · `:connection-error` · `:missing-price` · `:missing-data` · `:missing-metadata` · `:no-data` · `:invalid-opts` · `:timeout` · `:execution-error` · `:interrupted` · `:exception`

---

## Experimental: Fundamentals, Financials & Analyst Data

> ⚠️ Uses Yahoo's authenticated `quoteSummary` endpoint. Authentication is fully automatic (cookie + crumb, cached for one hour). No API key required. Works reliably today but Yahoo can change this at any time.

```clojure
(require '[clj-yfinance.experimental.fundamentals :as yff])
```

| Function | Returns |
|---|---|
| `fetch-fundamentals` / `*` | P/E, market cap, margins, revenue, analyst price targets |
| `fetch-company-info` / `*` | Sector, industry, description, employees, officers |
| `fetch-analyst` / `*` | EPS/revenue estimates, buy/hold/sell trends, earnings surprises |
| `fetch-financials` / `*` | Income statement, balance sheet, cash flow (annual or quarterly) |
| `fetch-calendar` / `*` | Upcoming earnings dates, EPS/revenue estimates, ex-dividend date |
| `fetch-quotesummary*` | Raw access to any `quoteSummary` module combination |

```clojure
;; Key fundamentals
(yff/fetch-fundamentals "AAPL")
;; => {:financialData      {:currentPrice    {:raw 255.78  :fmt "255.78"}
;;                          :recommendationKey "buy"
;;                          :profitMargins   {:raw 0.27    :fmt "27.04%"}
;;                          :targetMeanPrice {:raw 292.15  :fmt "292.15"} ...}
;;     :defaultKeyStatistics {:beta           {:raw 1.107   :fmt "1.11"}
;;                            :forwardPE      {:raw 27.54   :fmt "27.54"} ...}}

;; Company profile
(yff/fetch-company-info "AAPL")
;; => {:sector "Technology"
;;     :industry "Consumer Electronics"
;;     :fullTimeEmployees 150000
;;     :longBusinessSummary "Apple Inc. designs..."
;;     :companyOfficers [{:name "Mr. Timothy D. Cook" :title "CEO & Director" ...}]
;;     ...}

;; Analyst estimates
(yff/fetch-analyst "AAPL")
;; => {:earningsTrend       {:trend [{:period "0q" :earningsEstimate {:avg {:raw 1.95}} ...}]}
;;     :recommendationTrend {:trend [{:period "0m" :strongBuy 5 :buy 23 :hold 16 :sell 1}]}
;;     :earningsHistory     {:history [{:epsActual {:raw 1.65} :epsEstimate {:raw 1.62}
;;                                      :surprisePercent {:raw 0.0169}} ...]}}

;; Financial statements (annual by default; use :period :quarterly)
(yff/fetch-financials "MSFT")
(yff/fetch-financials "AAPL" :period :quarterly)

;; Upcoming earnings & dividend dates
(yff/fetch-calendar "AAPL")
;; => {:earnings {:earningsDate [{:raw 1777582800 :fmt "2026-04-30"}] ...}
;;     :exDividendDate {:raw 1770595200 :fmt "2026-02-09"} ...}

;; Raw module access
(yff/fetch-quotesummary* "AAPL" "assetProfile,earningsTrend")
```

**Data format note:** Yahoo returns numeric values as `{:raw <number> :fmt <string>}` maps. Use `:raw` for calculations, `:fmt` for display.

**Session management:**

```clojure
(require '[clj-yfinance.experimental.auth :as auth])
(auth/session-info)     ;; => {:status :active, :age-minutes 12.3, :crumb-present? true}
(auth/force-refresh!)   ;; force a new session
```

## Experimental: Options Chains

> ⚠️ Uses Yahoo's authenticated v7 options endpoint. Same session as fundamentals; same caveats apply.

```clojure
(require '[clj-yfinance.experimental.options :as yfo])

;; Nearest expiry + all available expiration dates
(yfo/fetch-options "AAPL")
;; => {:underlying-symbol "AAPL"
;;     :expiration-dates  [1771372800 1771977600 ...]
;;     :strikes           [195.0 200.0 210.0 ... 335.0]
;;     :calls             [{:contractSymbol "AAPL260218C00210000"
;;                          :strike 210.0 :bid 44.6 :ask 47.6
;;                          :impliedVolatility 1.447 :inTheMoney true ...} ...]
;;     :puts              [{...} ...]}

;; Specific expiry
(yfo/fetch-options "AAPL" :expiration 1771977600)
```

Each contract includes: `:contractSymbol` `:strike` `:bid` `:ask` `:lastPrice` `:impliedVolatility` `:openInterest` `:volume` `:inTheMoney` `:expiration` `:lastTradeDate` `:percentChange` `:change`.

---

## Data Science Integrations

### Dataset (tech.ml.dataset)

Add `tech.ml.dataset` as a dependency and use `clj-yfinance.dataset`:

```clojure
;; deps.edn
{:deps {techascent/tech.ml.dataset {:mvn/version "7.032"}}}
```

```clojure
(require '[clj-yfinance.dataset :as yfd])

(yfd/historical->dataset "AAPL" :period "1mo")
;; => #tech.v3.dataset [:timestamp :open :high :low :close :volume :adj-close]

(yfd/prices->dataset (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
(yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")
(yfd/dividends-splits->dataset "AAPL" :period "10y")
(yfd/info->dataset "AAPL")
```

Column types: timestamps as `:int64`, prices as `:float64`, volume as `:int64`, tickers as `:string`.

Works directly with tablecloth:

```clojure
(require '[tablecloth.api :as tc])

(-> (yfd/historical->dataset "AAPL" :period "1y")
    (tc/add-column :returns (fn [ds]
                              (let [c (ds :close)]
                                (map / (rest c) c))))
    (tc/select-columns [:timestamp :close :returns]))
```

For datasets too large to fit in memory, [Clojask](https://github.com/clojure-finance/clojask) can process the data out-of-core via CSV:

```clojure
(require '[tech.v3.dataset :as ds])
(require '[clojask.dataframe :as ck])
(ds/write! (yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "5y") "data.csv")
(def ck-df (ck/dataframe "data.csv"))
```

### Kindly (Clay / Portal)

For auto-rendering as interactive tables in [Clay](https://github.com/scicloj/clay) or [Portal](https://github.com/djblue/portal), use the `clj-yfinance.kindly` namespace. Same API as `clj-yfinance.dataset`, but output is tagged with `kind/dataset`.

```clojure
;; deps.edn alias (already in the project)
{:aliases {:kindly {:extra-deps {org.scicloj/kindly {:mvn/version "4-beta23"}
                                 techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

```clojure
(require '[clj-yfinance.kindly :as yfk])

(yfk/historical->dataset "AAPL" :period "1mo")
(yfk/prices->dataset (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
(yfk/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")
(yfk/dividends-splits->dataset "AAPL" :period "10y")
(yfk/info->dataset "AAPL")
```

### Parquet

Columnar archiving of financial datasets:

```clojure
;; deps.edn alias (already in the project)
{:aliases {:parquet {:extra-deps {com.techascent/tmd-parquet {:mvn/version "1.000-beta-39"}
                                  techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

```clojure
(require '[clj-yfinance.parquet :as yfp])

;; Save
(yfp/save-historical! "AAPL" "aapl.parquet" :period "5y")
(yfp/save-multi-ticker! ["AAPL" "GOOGL" "MSFT"] "tech.parquet" :period "1y")

;; Load
(yfp/load-historical "aapl.parquet")
(yfp/load-dataset "tech.parquet")

;; Save an already-transformed dataset
(yfp/save-dataset! my-enriched-ds "enriched.parquet")
```

Start your REPL with `clojure -M:parquet:nrepl`.

### DuckDB

Run SQL queries over financial datasets using an embedded [DuckDB](https://duckdb.org/) database:

```clojure
;; deps.edn alias (already in the project)
{:aliases {:duckdb {:extra-deps {com.techascent/tmducken {:mvn/version "0.10.1-01"}
                                 techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

DuckDB requires a native shared library (`libduckdb`). On Linux: `apt install libduckdb-dev`; on macOS: `brew install duckdb`. Alternatively, set `DUCKDB_HOME` to the directory containing the library.

```clojure
(require '[clj-yfinance.duckdb :as yf-db])

(def db (yf-db/open-db))                ;; in-memory
(def db (yf-db/open-db "finance.db"))    ;; persistent

;; Load and query
(yf-db/load-historical! db "AAPL" :period "1y")
(yf-db/query db "SELECT * FROM AAPL ORDER BY timestamp DESC LIMIT 5")

(yf-db/load-multi-ticker! db ["AAPL" "GOOGL" "MSFT"] :period "1y")
(yf-db/query db "SELECT ticker, AVG(close) AS avg_close FROM prices GROUP BY ticker ORDER BY avg_close DESC")

;; Load any existing dataset
(yf-db/load-dataset! db my-ds :table-name "enriched")

;; Cleanup
(yf-db/run! db "DROP TABLE IF EXISTS prices")
(yf-db/close! db)
```

`query` returns a dataset whose column names are keywords (e.g. `:ticker`, `:avg_close`), consistent with the rest of clj-yfinance — so `(ds/column result :avg_close)` works directly.

Start your REPL with `clojure -M:duckdb:nrepl`.

### Using with Noj

[Noj](https://github.com/scicloj/noj) is the Scicloj batteries-included data science toolkit — tablecloth, tableplot, fastmath, Clay, and more in a single tested dependency. clj-yfinance serves as the data acquisition layer.

```bash
clojure -M:noj:nrepl
```

```clojure
(require '[clj-yfinance.core    :as yf])
(require '[clj-yfinance.dataset :as yfd])
(require '[tablecloth.api       :as tc])
(require '[fastmath.stats       :as stats])
(require '[scicloj.kindly.v4.kind :as kind])
(require '[scicloj.clay.v2.api    :as clay])

;; Fetch → dataset → log-returns → summary stats → visualise
(def prices-ds (yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y"))

(defn log-returns [ds]
  (tc/add-column ds :log-return
    (fn [rows]
      (let [c (vec (rows :close))]
        (into [nil] (map (fn [a b] (Math/log (/ b a))) c (rest c)))))))

(def returns-ds
  (-> prices-ds
      (tc/group-by :ticker)
      (tc/process #(log-returns %))
      tc/ungroup))

(defn ticker-stats [ds ticker]
  (let [rets (->> (tc/select-rows ds #(= (:ticker %) ticker))
                  :log-return (remove nil?) vec)]
    {:ticker ticker
     :mean     (stats/mean rets)
     :std      (stats/stddev rets)
     :skewness (stats/skewness rets)
     :kurtosis (stats/kurtosis rets)
     :sharpe   (/ (stats/mean rets) (stats/stddev rets))}))

(kind/table (tc/dataset (map #(ticker-stats returns-ds %) ["AAPL" "GOOGL" "MSFT"])))
```

| Need | Library (via Noj) |
|---|---|
| Data wrangling | tablecloth |
| Charting | tableplot (Plotly/Vega-Lite) |
| Statistics | fastmath |
| ML / modelling | metamorph.ml |
| Notebook rendering | Clay + Kindly |

---

## Demo Notebook (Clay)

The `examples/finance_demo.clj` notebook demonstrates the full pipeline — fetching, transforming with tablecloth, and rendering interactive charts with tableplot.

**Covers:** current prices, historical OHLCV, log-returns, multi-ticker datasets, closing price charts, normalised performance (indexed to 100), rolling volatility, returns distributions, dividend history, and ticker info comparison.

### Running

Start a REPL with the `:clay` alias and evaluate in your editor:

```bash
clojure -M:clay:nrepl
```

Clay integrates with Calva (VS Code), CIDER (Emacs), and Cursive (IntelliJ). When you evaluate a form, Clay opens `http://localhost:1971/` and updates live.

To render the entire notebook to a static HTML file:

```clojure
(require '[scicloj.clay.v2.api :as clay])
(clay/make! {:source-path "examples/finance_demo.clj"})
```

---

## Error Types Reference

### Core Errors

`:rate-limited` · `:http-error` · `:api-error` · `:parse-error` · `:connection-error` · `:missing-price` · `:missing-data` · `:missing-metadata` · `:no-data` · `:invalid-opts` · `:timeout` · `:execution-error` · `:interrupted` · `:exception`

### Experimental Errors (in addition to core)

| Error | Cause |
|---|---|
| `:auth-failed` | Cookie/crumb refresh failed after retry |
| `:session-failed` | Session could not initialise (network issue) |
| `:request-failed` | Network error during authenticated request |
| `:missing-data` | Yahoo returned a response with no usable result |
| `:invalid-opts` | Invalid option value (e.g. bad `:period` for `fetch-financials`) |

---

## Development

### Running Tests

All tests are pure — no network calls. They cover URL encoding, validation, JSON parsing, retry behaviour, key normalisation, and dataset conversions.

```bash
# Core
clojure -M:test -e "(require 'clj-yfinance.core-test) (clj-yfinance.core-test/run-tests)"

# Experimental auth
clojure -M:test -e "(require 'clj-yfinance.experimental.auth-test) (clj-yfinance.experimental.auth-test/run-tests)"

# Experimental fundamentals
clojure -M:test -e "(require 'clj-yfinance.experimental.fundamentals-test) (clj-yfinance.experimental.fundamentals-test/run-tests)"

# Experimental options
clojure -M:test -e "(require 'clj-yfinance.experimental.options-test) (clj-yfinance.experimental.options-test/run-tests)"

# Dataset (requires tech.ml.dataset)
clojure -M:test:dataset -e "(require 'clj-yfinance.dataset-test) (clj-yfinance.dataset-test/run-tests)"

# Parquet (requires tmd-parquet + tech.ml.dataset)
clojure -M:test:parquet -e "(require 'clj-yfinance.parquet-test) (clj-yfinance.parquet-test/run-tests)"

# DuckDB (requires tmducken + tech.ml.dataset + native libduckdb)
clojure -M:test:duckdb -e "(require 'clj-yfinance.duckdb-test) (clj-yfinance.duckdb-test/run-tests)"
```

### REPL

```bash
clojure -M:nrepl   # starts nREPL on port 7888
```

---

## Caveats

- **No built-in caching** — every call hits the network. Add `core.memoize` or similar at the application level.
- **No built-in rate limiting** — aggressive parallel use triggers 429 errors. Use `:concurrency` on `fetch-prices` and retry logic via the verbose API.
- **Unofficial API** — Yahoo does not publicly document these endpoints. Check their Terms of Service for commercial use.
- **Financial statement coverage** — Yahoo restricts some balance sheet and cash flow fields. The income statement is the most complete module.

## Alternative Data Providers

For reference, commercial providers worth knowing about (no affiliation; pricing subject to change):

- **[Alpha Vantage](https://www.alphavantage.co/)** — Free tier; premium from ~$49.99/mo. Solid fundamentals and time series.
- **[Financial Modeling Prep](https://site.financialmodelingprep.com/)** — Free basic tier. 100+ endpoints, 70k+ securities, 30+ years of data.
- **[Massive](https://massive.com/)** (formerly Polygon.io) — Free basic; Starter $29/mo. Professional-grade market data and options.
- **[Finnhub](https://finnhub.io/)** — Free tier with generous limits. Real-time REST/WebSocket, global coverage.
- **[EOD Historical Data](https://eodhd.com/)** — Free tier (20 calls/day); from $19.99/mo. 60+ exchanges, student discounts.
- **[Marketstack](https://marketstack.com/)** — Free tier (100 req/mo); from ~$9.99/mo. 500k+ tickers, 15+ years historical.
- **[Twelve Data](https://twelvedata.com/)** — Stocks, forex, crypto, ETFs; 100k+ symbols.

## License

Apache-2.0 — see [LICENSE](LICENSE). (Artifacts through 0.1.7 were published under EPL-2.0; the switch applies from the next published version onward.)
