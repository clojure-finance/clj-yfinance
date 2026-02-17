# clj-yfinance

A pure Clojure client for Yahoo Finance. No Python bridge, no API key, no external HTTP dependencies — just Clojure and the Java 11 `HttpClient` that ships with the JDK.

The library has two tiers. The **stable core** covers prices, historical OHLCV, dividends, splits, and basic ticker metadata via Yahoo's public chart endpoint. The **experimental namespace** adds company fundamentals, analyst data, financial statements, and options chains via Yahoo's authenticated endpoints — these work today but may break if Yahoo changes their authentication mechanism.

## Installation

> **Not yet published to Clojars.** Clone the repo and use as a local dependency for now.

```bash
git clone https://github.com/clojure-finance/clj-yfinance.git
```

When published, coordinates will be:

```clojure
;; deps.edn
com.github.clojure-finance/clj-yfinance {:mvn/version "0.1.0"}

;; project.clj
[com.github.clojure-finance/clj-yfinance "0.1.0"]
```

**Requires JDK 11+** (uses `java.net.http.HttpClient`). The only runtime dependency is [Cheshire](https://github.com/dakrone/cheshire) for JSON parsing.

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
- **Financial statement coverage** — Yahoo restricts many balance sheet and cash flow fields. For complete financial statements use Financial Modeling Prep or Alpha Vantage.

If you need production-grade data with stable APIs and SLAs:

- [Alpha Vantage](https://www.alphavantage.co/) — free tier, solid fundamentals and time series
- [Financial Modeling Prep](https://site.financialmodelingprep.com/) — comprehensive financials, statements, and screening
- [Massive](https://massive.com/) (formerly Polygon.io) — professional-grade market data and options

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).
