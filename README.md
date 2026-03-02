# clj-yfinance

A pure Clojure client for Yahoo Finance. No Python bridge, no API key, no external HTTP dependencies — just Clojure and the Java 11 `HttpClient` that ships with the JDK.

The library has two tiers. The **stable core** covers prices, historical OHLCV, dividends, splits, and basic ticker metadata via Yahoo's public chart endpoint. The **experimental namespace** adds company fundamentals, analyst data, financial statements, and options chains via Yahoo's authenticated endpoints — these work today but may break if Yahoo changes their authentication mechanism.

## Installation

```clojure
;; deps.edn
com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.5"}

;; project.clj
[com.github.clojure-finance/clj-yfinance "0.1.5"]
```

**Requires JDK 11+** (uses `java.net.http.HttpClient`). The only runtime dependency is [charred](https://github.com/cnuernber/charred) for JSON parsing.

## Core API

All stable functions live in `clj-yfinance.core`. Every function comes in two flavours:

- **Simple API** (`fetch-price`, `fetch-historical`, …) — returns data directly, or `nil`/`[]` on failure. Use this when you just want the data.
- **Verbose API** (`fetch-price*`, `fetch-historical*`, …) — returns `{:ok? true :data …}` or `{:ok? false :error {…}}`. Use this when you need to distinguish error types, implement retries, or handle partial failures.

### Prices

```clojure
(require '[clj-yfinance.core :as yf])

;; Single ticker
(yf/fetch-price "AAPL")
;; => 261.05

;; Multiple tickers in parallel (bounded concurrency, default 8 threads)
(yf/fetch-prices ["AAPL" "GOOGL" "MSFT" "0005.HK"])
;; => {"AAPL" 261.05, "GOOGL" 337.28, "MSFT" 428.04, "0005.HK" 59.85}

;; Lower concurrency to avoid rate limiting
(yf/fetch-prices large-ticker-list :concurrency 2)

;; Verbose — per-ticker success/failure
(yf/fetch-prices* ["AAPL" "INVALID"])
;; => {"AAPL"    {:ok? true  :data 261.05}
;;     "INVALID" {:ok? false :error {:type :http-error :status 404 ...}}}
```

### Historical Data

```clojure
(import '[java.time Instant Duration])

;; Daily bars for the past month
(yf/fetch-historical "AAPL" :period "1mo")
;; => [{:timestamp 1704067200, :open 185.2, :high 186.1, :low 184.0,
;;      :close 185.5, :volume 12345678, :adj-close 184.9} ...]

;; Intraday with a custom date range
(yf/fetch-historical "TSLA"
                     :start (.minus (Instant/now) (Duration/ofDays 7))
                     :interval "1h"
                     :prepost true)   ; include pre/post market
```

**`:period`** — `1d` `5d` `1mo` `3mo` `6mo` `1y` `2y` `5y` `10y` `ytd` `max`  
**`:interval`** — `1m` `2m` `5m` `15m` `30m` `60m` `90m` `1h` `1d` `5d` `1wk` `1mo` `3mo`  
**`:start` / `:end`** — epoch seconds (integer) or `java.time.Instant`; `:start` overrides `:period`  
**`:adjusted`** — include adjusted close (default `true`)  
**`:prepost`** — include pre/post market data (default `false`)

Invalid parameter combinations (unknown period/interval, `:end` without `:start`, `start > end`) are caught before any network call and returned as `:invalid-opts` errors. Technically valid but potentially problematic combinations (e.g. `1m` interval over a 30-day range) return a warning in the `:warnings` key of the verbose response rather than failing outright.

### Dividends & Splits

```clojure
(yf/fetch-dividends-splits "AAPL" :period "10y")
;; => {:dividends {1699574400 {:amount 0.24 :date 1699574400} ...}
;;     :splits    {1598832000 {:numerator 4 :denominator 1 ...} ...}}
```

Accepts the same `:period`, `:start`, `:end` options as `fetch-historical`. Default period is `"5y"`.

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

Returns basic metadata from Yahoo's public chart endpoint: identifiers, current price, day/52-week ranges, exchange info. For richer company data (sector, description, officers, P/E) use `fetch-company-info` in the experimental namespace.

### Error Handling

The verbose API gives you structured errors with enough context to react intelligently:

```clojure
;; Distinguish error types
(let [result (yf/fetch-price* "AAPL")]
  (if (:ok? result)
    (:data result)
    (case (-> result :error :type)
      :rate-limited  (do (Thread/sleep 5000) (yf/fetch-price "AAPL"))
      :http-error    (println "Bad ticker or endpoint:" (-> result :error :status))
      :parse-error   (println "Yahoo changed their format")
      nil)))

;; Batch fetch — collect successes and failures separately
(let [results  (yf/fetch-prices* ["AAPL" "GOOGL" "INVALID"])
      ok?      (fn [[_ r]] (:ok? r))
      prices   (into {} (map (fn [[t r]] [t (:data r)])  (filter ok? results)))
      errors   (into {} (map (fn [[t r]] [t (-> r :error :type)]) (remove ok? results)))]
  {:prices prices :errors errors})
;; => {:prices {"AAPL" 261.05, "GOOGL" 337.28}
;;     :errors {"INVALID" :http-error}}
```

**Core error types:** `:rate-limited` · `:http-error` · `:api-error` · `:parse-error` · `:connection-error` · `:missing-price` · `:missing-data` · `:missing-metadata` · `:no-data` · `:invalid-opts` · `:timeout` · `:execution-error` · `:interrupted` · `:exception`

## Dataset Integration

For use with the Clojure data science stack (tablecloth, noj, datajure), add tech.ml.dataset as an optional dependency:

```clojure
;; deps.edn
{:deps {techascent/tech.ml.dataset {:mvn/version "7.032"}}}
```

Then use the `clj-yfinance.dataset` namespace:

```clojure
(require '[clj-yfinance.core    :as yf])
(require '[clj-yfinance.dataset :as yfd])

;; Historical data as a typed dataset
(yfd/historical->dataset "AAPL" :period "1mo")
;; => #tech.v3.dataset [:timestamp :open :high :low :close :volume :adj-close]
;;    (int64, float64 x5, int64)

;; Price map → dataset
(yfd/prices->dataset (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
;; => #tech.v3.dataset [:ticker :price]

;; Multi-ticker combined dataset with :ticker grouping column
(yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")

;; Dividends and splits as separate datasets
(yfd/dividends-splits->dataset "AAPL" :period "10y")
;; => {:dividends #tech.v3.dataset, :splits #tech.v3.dataset}

;; Ticker info as a single-row dataset
(yfd/info->dataset "AAPL")
```

Column types: timestamps as `:int64` (Unix epoch seconds), prices as `:float64`, volume as `:int64`, tickers as `:string`. Convert timestamps with `java.time.Instant/ofEpochSecond`.

The dataset namespace integrates directly with the rest of the Clojure data science ecosystem:

```clojure
(require '[tablecloth.api :as tc])

(-> (yfd/historical->dataset "AAPL" :period "1y")
    (tc/add-column :returns (fn [ds]
                              (let [c (ds :close)]
                                (map / (rest c) c))))
    (tc/select-columns [:timestamp :close :returns]))
```

For datasets too large to fit in memory, [Clojask](https://github.com/clojure-finance/clojask) can process the data out-of-core. The simplest bridge is writing the dataset to CSV with `ds/write!` and reading it into Clojask with `ck/dataframe`.

```clojure
(require '[tech.v3.dataset :as ds])
(require '[clojask.dataframe :as ck])
(ds/write! (yfd/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "5y") "data.csv")
(def ck-df (ck/dataframe "data.csv"))
```

## Kindly Integration

For use with [Clay](https://github.com/scicloj/clay), [Portal](https://github.com/djblue/portal), and any other [Kindly](https://github.com/scicloj/kindly)-aware tool, add the `:kindly` alias and use the `clj-yfinance.kindly` namespace:

```clojure
;; deps.edn alias (already included in the project's deps.edn)
{:aliases {:kindly {:extra-deps {org.scicloj/kindly {:mvn/version "4-beta23"}
                                 techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

```clojure
(require '[clj-yfinance.kindly :as yfk])

;; Same API as clj-yfinance.dataset but output is tagged with kind/dataset —
;; auto-renders as an interactive table in Clay, Portal, etc.
(yfk/historical->dataset "AAPL" :period "1mo")
(yfk/prices->dataset (yf/fetch-prices ["AAPL" "GOOGL" "MSFT"]))
(yfk/multi-ticker->dataset ["AAPL" "GOOGL" "MSFT"] :period "1y")
(yfk/dividends-splits->dataset "AAPL" :period "10y")
;; => {:dividends <kind/dataset> :splits <kind/dataset>}
(yfk/info->dataset "AAPL")
```

## Demo Notebook (Clay)

The `examples/finance_demo.clj` notebook demonstrates the full pipeline — fetching data, transforming it with tablecloth, and rendering interactive charts with tableplot — inside a [Clay](https://github.com/scicloj/clay) notebook.

**What the notebook covers:**

1. Current prices for a basket of tickers
2. Historical OHLCV data as a typed dataset
3. Tablecloth transformations (log-returns, date formatting)
4. Multi-ticker combined dataset
5. Closing price chart (interactive Plotly)
6. Normalised performance chart (indexed to 100)
7. Daily log-returns chart
8. 20-day rolling volatility (annualised)
9. Returns distribution (density plot)
10. Dividend history
11. Ticker info comparison table

### Setup

Add the `:clay` alias to your `deps.edn` (already included in the project's `deps.edn`):

```clojure
:clay {:extra-paths ["examples"]
       :extra-deps {org.scicloj/clay       {:mvn/version "2-beta56"}
                    scicloj/tablecloth      {:mvn/version "7.062"}
                    org.scicloj/tableplot   {:mvn/version "1-beta14"}
                    techascent/tech.ml.dataset {:mvn/version "7.032"}}}
```

### Running with your editor

Start a REPL with the `:clay` alias:

```bash
clojure -M:clay:nrepl
```

Then evaluate the namespace in your editor. Clay integrates with all major Clojure editors:

- **Calva** (VS Code) — use the Clay commands from the command palette
- **CIDER** (Emacs) — use the Clay CIDER commands (see [Clay setup](https://scicloj.github.io/clay/#setup))
- **Cursive** (IntelliJ) — add Clay REPL commands via `.idea/repl-commands.xml`

When you evaluate a form, Clay opens `http://localhost:1971/` in your browser and updates it live as you evaluate more forms.

### Rendering to HTML

To render the entire notebook to a static HTML file:

```clojure
(require '[scicloj.clay.v2.api :as clay])

;; Render and open in browser
(clay/make! {:source-path "examples/finance_demo.clj"})

;; Render to file without opening browser
(clay/make! {:source-path "examples/finance_demo.clj"
             :show false})
```

The output is written to `docs/finance_demo.html` by default.

## Parquet Integration

For columnar archiving of financial datasets, add the `:parquet` alias and use the `clj-yfinance.parquet` namespace:

```clojure
;; deps.edn alias (already included in the project's deps.edn)
{:aliases {:parquet {:extra-deps {com.techascent/tmd-parquet {:mvn/version "1.000-beta-39"}
                                  techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

```clojure
(require '[clj-yfinance.parquet :as yfp])

;; Fetch and save one ticker
(yfp/save-historical! "AAPL" "aapl.parquet" :period "5y")

;; Fetch and save multiple tickers in one file (includes :ticker column)
(yfp/save-multi-ticker! ["AAPL" "GOOGL" "MSFT"] "tech.parquet" :period "1y")

;; Load back as a dataset with keyword column names
(yfp/load-historical "aapl.parquet")
(yfp/load-dataset "tech.parquet")

;; Save an already-transformed dataset
(require '[tablecloth.api :as tc])
(-> (yfd/historical->dataset "AAPL" :period "1y")
    (tc/add-column :log-return ...)
    (yfp/save-dataset! "aapl-enriched.parquet"))
```

Start your REPL with `clojure -M:parquet:nrepl` to use this namespace.

## DuckDB Integration

For running SQL queries over financial datasets using an embedded [DuckDB](https://duckdb.org/) database, add the `:duckdb` alias and use the `clj-yfinance.duckdb` namespace:

```clojure
;; deps.edn alias (already included in the project's deps.edn)
{:aliases {:duckdb {:extra-deps {com.techascent/tmducken {:mvn/version "0.10.1-01"}
                                 techascent/tech.ml.dataset {:mvn/version "7.032"}}}}}
```

DuckDB also requires a native shared library (`libduckdb`). On most Linux distributions you can install it via your package manager (e.g. `apt install libduckdb-dev`). Alternatively, set the `DUCKDB_HOME` environment variable to the directory containing the library before starting your REPL. On macOS it is available via Homebrew (`brew install duckdb`).

```clojure
(require '[clj-yfinance.duckdb :as yf-db])

;; Open an in-memory database
(def db (yf-db/open-db))

;; Open a persistent on-disk database
(def db (yf-db/open-db "finance.db"))

;; Load historical data for a single ticker (table named after the ticker)
(yf-db/load-historical! db "AAPL" :period "1y")
(yf-db/query db "SELECT * FROM AAPL ORDER BY timestamp DESC LIMIT 5")
;; => #tech.v3.dataset [:timestamp :open :high :low :close :volume :adj-close]

;; Load multiple tickers into a single "prices" table (includes :ticker column)
(yf-db/load-multi-ticker! db ["AAPL" "GOOGL" "MSFT"] :period "1y")
(yf-db/query db "SELECT ticker, AVG(close) AS avg_close FROM prices GROUP BY ticker ORDER BY avg_close DESC")
;; => #tech.v3.dataset [:ticker :avg_close]

;; Load any existing dataset into a named table
(yf-db/load-dataset! db my-ds :table-name "enriched")

;; Run DDL without returning a result
(yf-db/run! db "DROP TABLE IF EXISTS prices")

;; Close when done
(yf-db/close! db)
```

Start your REPL with `clojure -M:duckdb:nrepl` to use this namespace.

## Using with Noj

[Noj](https://github.com/scicloj/noj) is the Scicloj batteries-included data science toolkit — it bundles tablecloth, tableplot, fastmath, Clay, and more into a single tested dependency. clj-yfinance pairs naturally with it as the data acquisition layer.

Add the `:noj` alias (already included in the project's `deps.edn`):

```clojure
;; deps.edn alias
{:aliases {:noj {:extra-deps {org.scicloj/noj {:mvn/version "2-beta18"}}}}}
```

Start your REPL with both aliases:

```bash
clojure -M:noj:nrepl
```

### Full pipeline: fetch → tablecloth → fastmath → Clay

```clojure
(require '[clj-yfinance.core    :as yf])
(require '[clj-yfinance.dataset :as yfd])
(require '[tablecloth.api       :as tc])
(require '[fastmath.stats       :as stats])
(require '[scicloj.kindly.v4.kind :as kind])
(require '[scicloj.clay.v2.api    :as clay])
```

**Step 1 — Fetch and convert to a dataset:**

```clojure
(def tickers ["AAPL" "GOOGL" "MSFT" "0005.HK"])

(def prices-ds
  (yfd/multi-ticker->dataset tickers :period "1y"))
```

**Step 2 — Compute log-returns with tablecloth:**

```clojure
(defn log-returns [ds]
  (tc/add-column ds :log-return
    (fn [rows]
      (let [c (vec (rows :close))]
        (into [nil]
              (map (fn [a b] (Math/log (/ b a)))
                   c (rest c)))))))

(def returns-ds
  (-> prices-ds
      (tc/group-by :ticker)
      (tc/process #(log-return %))
      tc/ungroup))
```

**Step 3 — Summary statistics with fastmath:**

```clojure
(defn ticker-stats [ds ticker]
  (let [rets (->> (tc/select-rows ds #(= (:ticker %) ticker))
                  :log-return
                  (remove nil?)
                  vec)]
    {:ticker   ticker
     :mean     (stats/mean rets)
     :std      (stats/stddev rets)
     :skewness (stats/skewness rets)
     :kurtosis (stats/kurtosis rets)
     :sharpe   (/ (stats/mean rets) (stats/stddev rets))}))

(def summary
  (map #(ticker-stats returns-ds %) tickers))
```

**Step 4 — Visualise and render with Clay:**

```clojure
;; Render as an interactive Clay notebook
(clay/make! {:source-path "examples/finance_demo.clj"})

;; Or tag individual values for inline rendering in a notebook namespace
(kind/table (tc/dataset summary))
```

### What Noj adds over the base stack

| Need | Library (via Noj) |
|------|-------------------|
| Data wrangling | tablecloth |
| Charting | tableplot (Plotly/Vega-Lite) |
| Statistics | fastmath |
| ML / modelling | metamorph.ml |
| Notebook rendering | Clay + Kindly |

Because Noj pulls in all these libraries with tested, compatible versions, you can mix and match without worrying about dependency conflicts. clj-yfinance handles the data acquisition; Noj handles everything downstream.

## Experimental: Fundamentals & Company Data

> ⚠️ **EXPERIMENTAL** — uses Yahoo's authenticated `quoteSummary` endpoint via a cookie/crumb session. Works reliably today but Yahoo can change or revoke this at any time without notice. Treat as best-effort, not production-grade.

```clojure
(require '[clj-yfinance.experimental.fundamentals :as yff])
```

Authentication is fully automatic — the library fetches a session cookie from `fc.yahoo.com` and a crumb token from Yahoo's API on first use, caches the session, and refreshes it after one hour. No API key or manual setup required.

### Available Functions

| Function | Returns |
|----------|---------|
| `fetch-fundamentals` / `*` | P/E, market cap, margins, revenue, analyst price targets |
| `fetch-company-info` / `*` | Sector, industry, description, employees, executive officers |
| `fetch-analyst` / `*` | EPS/revenue estimates, buy/hold/sell trends, earnings surprises |
| `fetch-financials` / `*` | Income statement, balance sheet, cash flow (annual or quarterly) |
| `fetch-calendar` / `*` | Upcoming earnings dates, call dates, EPS/revenue estimates, ex-dividend date |
| `fetch-quotesummary*` | Raw access to any `quoteSummary` module combination |

### Usage

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
;;     :companyOfficers [{:name "Mr. Timothy D. Cook" :title "CEO & Director"
;;                        :totalPay {:raw 16759518 :fmt "16.76M"}} ...]
;;     :website "https://www.apple.com" ...}

;; Analyst estimates and recommendations
(yff/fetch-analyst "AAPL")
;; => {:earningsTrend       {:trend [{:period "0q"
;;                                    :earningsEstimate {:avg {:raw 1.95} ...}
;;                                    :epsTrend {:current {:raw 1.95}
;;                                               :30daysAgo {:raw 1.85}} ...}]}
;;     :recommendationTrend {:trend [{:period "0m"
;;                                    :strongBuy 5 :buy 23 :hold 16 :sell 1}]}
;;     :earningsHistory     {:history [{:epsActual {:raw 1.65}
;;                                      :epsEstimate {:raw 1.62}
;;                                      :surprisePercent {:raw 0.0169}} ...]}}

;; Financial statements (annual by default)
(yff/fetch-financials "MSFT")
;; => {:incomeStatementHistory
;;     {:incomeStatementHistory
;;      [{:endDate      {:raw 1751241600 :fmt "2025-06-30"}
;;        :totalRevenue {:raw 281724000000 :fmt "281.72B"}
;;        :netIncome    {:raw 101832000000 :fmt "101.83B"}} ...]}}

;; Quarterly
(yff/fetch-financials "AAPL" :period :quarterly)
;; => {:incomeStatementHistoryQuarterly   {...}
;;     :balanceSheetHistoryQuarterly      {...}
;;     :cashflowStatementHistoryQuarterly {...}}

;; Upcoming earnings dates and dividend schedule
(yff/fetch-calendar "AAPL")
;; => {:earnings {:earningsDate [{:raw 1777582800 :fmt "2026-04-30"}]
;;                :earningsCallDate [{:raw 1769724000 :fmt "2026-01-29"}]
;;                :isEarningsDateEstimate false
;;                :earningsAverage {:raw 1.95403 :fmt "1.95"}
;;                :earningsHigh {:raw 2.16 :fmt "2.16"}
;;                :earningsLow {:raw 1.85 :fmt "1.85"}
;;                :revenueAverage {:raw 109083851330 :fmt "109.08B"} ...}
;;     :exDividendDate {:raw 1770595200 :fmt "2026-02-09"}
;;     :dividendDate   {:raw 1770854400 :fmt "2026-02-12"}}

;; Quick access to just the next earnings date string
(-> (yff/fetch-calendar "AAPL") :earnings :earningsDate first :fmt)
;; => "2026-04-30"

;; Raw module access
(yff/fetch-quotesummary* "AAPL" "assetProfile,earningsTrend")
;; => {:ok? true :data {:assetProfile {...} :earningsTrend {...}}}
```

**On data format:** Yahoo returns numeric values as `{:raw <number> :fmt <string>}` maps. Use `:raw` for calculations, `:fmt` for display. Some fields (e.g. `:recommendationKey`) are plain strings.

**On financial statements:** Yahoo partially restricts balance sheet and cash flow fields. Only `:totalRevenue`, `:netIncome`, and `:endDate` are reliably available; the income statement is the most useful module.

**On session management:**

```clojure
(require '[clj-yfinance.experimental.auth :as auth])

(auth/session-info)
;; => {:status :active, :age-minutes 12.3, :crumb-present? true}

(auth/force-refresh!)   ; force a new session if needed
```

## Experimental: Options Chains

> ⚠️ **EXPERIMENTAL** — uses Yahoo's authenticated v7 options endpoint. Same cookie/crumb session as the fundamentals namespace; same stability caveats apply.

```clojure
(require '[clj-yfinance.experimental.options :as yfo])

;; Nearest expiry + full list of available expiration dates
(yfo/fetch-options "AAPL")
;; => {:underlying-symbol "AAPL"
;;     :expiration-dates  [1771372800 1771977600 ...]   ; all dates, epoch seconds
;;     :strikes           [195.0 200.0 210.0 ... 335.0]
;;     :expiration-date   1771372800
;;     :quote             {:regularMarketPrice 255.78 ...}
;;     :calls             [{:contractSymbol  "AAPL260218C00210000"
;;                          :strike          210.0
;;                          :bid             44.6   :ask          47.6
;;                          :lastPrice       46.1
;;                          :impliedVolatility 1.447
;;                          :openInterest    100    :volume       50
;;                          :inTheMoney      true
;;                          :expiration      1771372800
;;                          :lastTradeDate   1771200000} ...]
;;     :puts              [{:contractSymbol "AAPL260218P00210000"
;;                          :strike 210.0 :inTheMoney false ...} ...]}

;; Specific expiry — use an epoch seconds value from :expiration-dates
(yfo/fetch-options "AAPL" :expiration 1771977600)
;; => {:calls [...] :puts [...] :expiration-date 1771977600 ...}

;; Verbose API
(let [result (yfo/fetch-options* "AAPL")]
  (if (:ok? result)
    (:data result)
    (println "Error:" (-> result :error :type)
             "—" (-> result :error :suggestion))))
```

Each contract map includes: `:contractSymbol` `:strike` `:bid` `:ask` `:lastPrice` `:impliedVolatility` `:openInterest` `:volume` `:inTheMoney` `:expiration` `:lastTradeDate` `:percentChange` `:change`.

## Experimental Error Types

All experimental functions share the same error vocabulary, in addition to the core error types listed above:

| Error | Cause |
|-------|-------|
| `:auth-failed` | Cookie/crumb refresh failed after retry |
| `:session-failed` | Session could not initialize (network issue) |
| `:request-failed` | Network error during authenticated request |
| `:missing-data` | Yahoo returned a response with no usable result |
| `:invalid-opts` | Invalid option value (e.g. bad `:period` for `fetch-financials`) |

## Development

### Running Tests

All tests are pure — no network calls. They cover URL encoding, validation logic, JSON parsing, retry behaviour, key normalisation, and dataset conversions using fixtures.

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

## Caveats & Alternatives

The stable core (prices, historical data, dividends, info) uses Yahoo's public chart endpoint, which has been stable for years and requires no authentication. The experimental namespaces use Yahoo's authenticated endpoints, which work today but carry no guarantees.

Specific limitations worth knowing:

- **No built-in caching** — every call hits the network. Add `core.memoize` or similar at the application level if needed.
- **No built-in rate limiting** — aggressive parallel use will trigger 429 errors. Use the `:concurrency` option on `fetch-prices` and implement retry logic via the verbose API.
- **Unofficial API** — Yahoo does not publicly document these endpoints. Check their Terms of Service before using in a commercial application.
- **Financial statement coverage** — Yahoo restricts many balance sheet and cash flow fields. For complete financial statements see the Data Providers section below.

## Other Financial Data Providers

For reference, commercial providers worth knowing about (no affiliation or endorsement; pricing and features subject to change):

- **[Alpha Vantage](https://www.alphavantage.co/)** — Free tier with solid fundamentals and time series; premium plans start around $49.99/mo.
- **[Financial Modeling Prep](https://site.financialmodelingprep.com/)** — Free basic tier. 100+ endpoints for comprehensive financials, statements, and screening; 70k+ securities; 30+ years of data.
- **[Massive](https://massive.com/)** (formerly Polygon.io) — Free basic tier; Starter at $29/mo. Professional-grade market data and options; 20+ years historical.
- **[Finnhub](https://finnhub.io/)** — Free tier with generous limits; real-time REST/WebSocket for stocks, forex, crypto; global coverage and alternative data.
- **[EOD Historical Data](https://eodhd.com/)** — Free tier (20 calls/day); paid plans from $19.99/mo. Historical/real-time/fundamentals for 60+ exchanges; student discounts available.
- **[Marketstack](https://marketstack.com/)** — Free tier (100 req/mo); paid plans from ~$9.99/mo. EOD/intraday/real-time for 500k+ tickers; 15+ years historical.
- **[Twelve Data](https://twelvedata.com/)** — Unified APIs/WebSocket for stocks, forex, crypto, ETFs; 100k+ symbols.

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
