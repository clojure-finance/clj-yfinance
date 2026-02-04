(ns clj-yfinance.core
  "Pure Clojure Yahoo Finance client using Java HttpClient.
   No Python dependency, no API key required.
   
   Provides access to:
   - Current prices (single and parallel fetch)
   - Historical OHLCV data with full customization
   - Dividends and stock splits
   - Basic ticker information and metadata
   
   Note: Financial statements, options data, and comprehensive fundamentals
   require Yahoo's authenticated endpoints which are currently blocked.
   For these features, consider using AlphaVantage, Financial Modeling Prep,
   or other dedicated financial data APIs."
  (:require [clj-yfinance.http :as http]
            [clj-yfinance.parse :as parse]
            [clj-yfinance.validation :as validation])
  (:import [java.util.concurrent Executors Callable TimeUnit]))

(defn- fetch-chart*
  "Unified low-level chart fetch. All public fetch functions build on this.
   Returns standardized envelope: {:ok? bool :data/:error ... :warnings [...] :request {...}}
   
   Options:
     :interval  - Chart interval (1m, 5m, 1h, 1d, etc.)
     :period    - Time period (1d, 1mo, 1y, etc.)
     :start     - Start timestamp (Unix epoch or Instant)
     :end       - End timestamp (Unix epoch or Instant)
     :events    - Event types (e.g., 'div|split')
     :adjusted  - Include adjusted close (default true)
     :prepost   - Include pre/post market (default false)"
  [ticker {:keys [interval period start end events adjusted prepost]
           :or {adjusted true prepost false}}]
  (let [warnings (if interval
                   (validation/interval-range-warnings {:interval interval :period period :start start :end end})
                   [])
        params (cond-> {}
                 interval (assoc :interval interval)
                 true (merge (validation/chart-time-params {:period period :start start :end end}))
                 events (assoc :events events)
                 adjusted (assoc :includeAdjustedClose "true")
                 prepost (assoc :includePrePost "true"))
        query (parse/build-query params)
        result (http/try-fetch* ticker query)]
    (cond-> result
      true (assoc :request {:ticker ticker :query-params params})
      (seq warnings) (assoc :warnings warnings))))

(defn fetch-price*
  "Fetch current price for a single ticker. Returns structured result {:ok? true :data price} or {:ok? false :error {...}}."
  [ticker]
  (let [result (fetch-chart* ticker {:interval "1d" :period "1d"})]
    (if-not (:ok? result)
      result
      (if-let [price (-> result :data :meta :regularMarketPrice)]
        (assoc result :data price)
        (assoc result :ok? false :error {:type :missing-price :ticker ticker})))))

(defn fetch-price
  "Fetch current price for a single ticker. Returns price number or nil on failure."
  [ticker]
  (let [result (fetch-price* ticker)]
    (when (:ok? result)
      (:data result))))

(defn fetch-prices*
  "Fetch prices for multiple tickers in parallel with bounded concurrency.
   Returns {ticker {:ok? ... :data/:error ...}} map.
   
   Options:
     :concurrency - Maximum number of concurrent requests (default 8)"
  [tickers & {:keys [concurrency] :or {concurrency 8}}]
  (if-not (and (integer? concurrency) (pos? concurrency))
    (into {} (map (fn [t]
                    [t {:ok? false
                        :error {:type :invalid-opts
                                :message "Concurrency must be a positive integer"
                                :value concurrency}}])
                  tickers))
    (let [executor (Executors/newFixedThreadPool concurrency)
          timeout-sec 20]
      (try
        (let [futures (mapv (fn [t]
                              (.submit executor
                                       ^Callable
                                       (fn [] [t (fetch-price* t)])))
                            tickers)
              results (mapv (fn [f t]
                              (try
                                (.get f timeout-sec TimeUnit/SECONDS)
                                (catch java.util.concurrent.TimeoutException _
                                  [t {:ok? false
                                      :error {:type :timeout
                                              :ticker t
                                              :message "Price fetch timed out"}}])
                                (catch java.util.concurrent.ExecutionException e
                                  [t {:ok? false
                                      :error {:type :execution-error
                                              :ticker t
                                              :message (.getMessage (.getCause e))}}])
                                (catch InterruptedException _
                                  [t {:ok? false
                                      :error {:type :interrupted
                                              :ticker t
                                              :message "Price fetch interrupted"}}])))
                            futures
                            tickers)]
          (into {} results))
        (finally
          (.shutdown executor)
          (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
            (.shutdownNow executor)))))))

(defn fetch-prices
  "Fetch prices for multiple tickers in parallel. Returns {ticker price} map, omitting failures.
   
   Options:
     :concurrency - Maximum number of concurrent requests (default 8)"
  [tickers & {:keys [concurrency] :or {concurrency 8}}]
  (->> (fetch-prices* tickers :concurrency concurrency)
       (keep (fn [[t result]]
               (when (:ok? result)
                 [t (:data result)])))
       (into {})))

(defn fetch-historical*
  "Fetch historical OHLCV data for a ticker. Returns structured result {:ok? true :data [...] :request {...} :warnings [...]} or {:ok? false :error {...}}.
   
   Options:
     :period    - 1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd, max (default 1y)
     :interval  - 1m, 2m, 5m, 15m, 30m, 60m, 90m, 1h, 1d, 5d, 1wk, 1mo, 3mo (default 1d)
     :start     - Epoch seconds (integer) or Instant (overrides :period)
     :end       - Epoch seconds (integer) or Instant (requires :start; defaults to now)
     :adjusted  - Include adjusted close (default true)
     :prepost   - Include pre/post market data (default false)
   
   Validation behavior:
     - Invalid values (period/interval not in allowed sets, :end without :start, start > end) 
       are rejected immediately with :invalid-opts error
     - Valid but incompatible combinations (e.g., 1m interval with 1mo period exceeding 7-day limit)
       generate warnings in :warnings key but don't fail—Yahoo's API will reject truly invalid requests
   
   Note: For dividend/split events, use fetch-dividends-splits* instead."
  [ticker & {:keys [period interval start end adjusted prepost]
             :or {period "1y" interval "1d" adjusted true prepost false}}]
  (let [validation-result (validation/validate-opts {:period period :interval interval :start start :end end})]
    (if-not (:ok? validation-result)
      validation-result
      (try
        (let [result (fetch-chart* ticker {:interval interval
                                           :period period
                                           :start start
                                           :end end
                                           :adjusted adjusted
                                           :prepost prepost})]
          (if-not (:ok? result)
            result
            (let [data (:data result)
                  times (:timestamp data)
                  quotes (-> data :indicators :quote first)
                  adj (-> data :indicators :adjclose first :adjclose)]
              (cond
                (not times)
                (assoc result :ok? false
                       :error {:type :missing-data
                               :ticker ticker
                               :message "Missing timestamp data"})

                (not quotes)
                (assoc result :ok? false
                       :error {:type :missing-data
                               :ticker ticker
                               :message "Missing quote data"})

                (not (every? sequential? [(:open quotes) (:high quotes) (:low quotes)
                                          (:close quotes) (:volume quotes)]))
                (assoc result :ok? false
                       :error {:type :missing-data
                               :ticker ticker
                               :message "Missing required OHLCV fields"})

                :else
                (assoc result :data
                       (mapv (fn [t o h l c v a]
                               (cond-> {:timestamp t
                                        :open o :high h :low l :close c
                                        :volume v}
                                 (and adjusted a) (assoc :adj-close a)))
                             times
                             (:open quotes)
                             (:high quotes)
                             (:low quotes)
                             (:close quotes)
                             (:volume quotes)
                             (or adj (repeat nil))))))))
        (catch Exception e
          (parse/err {:type :exception :ticker ticker :message (.getMessage e)}))))))

(defn fetch-historical
  "Fetch historical OHLCV data for a ticker. Returns vector of maps, empty on failure.
   
   Options:
     :period    - 1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd, max (default 1y)
     :interval  - 1m, 2m, 5m, 15m, 30m, 60m, 90m, 1h, 1d, 5d, 1wk, 1mo, 3mo (default 1d)
     :start     - Epoch seconds (integer) or Instant (overrides :period)
     :end       - Epoch seconds (integer) or Instant (requires :start; defaults to now)
     :adjusted  - Include adjusted close (default true)
     :prepost   - Include pre/post market data (default false)
   
   Note: For dividend/split events, use fetch-dividends-splits instead."
  [ticker & {:keys [period interval start end adjusted prepost]
             :or {period "1y" interval "1d" adjusted true prepost false}}]
  (let [result (fetch-historical* ticker
                                  :period period :interval interval
                                  :start start :end end
                                  :adjusted adjusted :prepost prepost)]
    (if (:ok? result)
      (:data result)
      [])))

(defn fetch-dividends-splits*
  "Fetch dividend and split events for a ticker. Returns structured result {:ok? true :data {:dividends ... :splits ...}} or {:ok? false :error {...}}.
   
   Options:
     :period - 1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd, max (default 5y)
     :start  - Epoch seconds (integer) or Instant (overrides :period)
     :end    - Epoch seconds (integer) or Instant (requires :start; defaults to now)"
  [ticker & {:keys [period start end] :or {period "5y"}}]
  (let [validation-result (validation/validate-opts {:period period :start start :end end})]
    (if-not (:ok? validation-result)
      validation-result
      (try
        (let [result (fetch-chart* ticker {:interval "1d"
                                           :period period
                                           :start start
                                           :end end
                                           :events "div|split"})]
          (if-not (:ok? result)
            result
            (let [events (-> result :data :events)]
              (assoc result :data {:dividends (or (:dividends events) {})
                                   :splits (or (:splits events) {})}))))
        (catch Exception e
          (parse/err {:type :exception :ticker ticker :message (.getMessage e)}))))))

(defn fetch-dividends-splits
  "Fetch dividend and split events for a ticker. Returns {:dividends ... :splits ...} or empty maps on failure.
   
   Options:
     :period - 1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y, 10y, ytd, max (default 5y)
     :start  - Epoch seconds (integer) or Instant (overrides :period)
     :end    - Epoch seconds (integer) or Instant (requires :start; defaults to now)"
  [ticker & {:keys [period start end] :or {period "5y"}}]
  (let [result (fetch-dividends-splits* ticker :period period :start start :end end)]
    (if (:ok? result)
      (:data result)
      {:dividends {} :splits {}})))

(defn fetch-info*
  "Fetch basic ticker information and metadata. Returns structured result {:ok? true :data {...}} or {:ok? false :error {...}}.
   
   Returns a map with available company info including:
   - :symbol, :long-name, :short-name
   - :currency, :exchange-name, :instrument-type
   - :regular-market-price, :regular-market-volume
   - :regular-market-day-high, :regular-market-day-low
   - :fifty-two-week-high, :fifty-two-week-low
   - :timezone, :gmt-offset
   
   Note: This is basic info from the chart endpoint. For comprehensive
   company info (P/E ratio, market cap, EPS, sector, industry, description),
   Yahoo's quoteSummary endpoint is required but currently blocked by
   authentication requirements. Consider using AlphaVantage or FMP for
   comprehensive fundamentals."
  [ticker]
  (try
    (let [result (fetch-chart* ticker {:interval "1d" :period "1d"})]
      (if-not (:ok? result)
        result
        (let [meta (-> result :data :meta)]
          (if-not meta
            (assoc result :ok? false :error {:type :missing-metadata :ticker ticker})
            (assoc result :data
                   (-> meta
                       (select-keys [:symbol :longName :shortName :currency :exchangeName
                                     :fullExchangeName :instrumentType :regularMarketPrice
                                     :regularMarketVolume :regularMarketDayHigh :regularMarketDayLow
                                     :fiftyTwoWeekHigh :fiftyTwoWeekLow :chartPreviousClose
                                     :timezone :gmtoffset :exchangeTimezoneName :firstTradeDate
                                     :regularMarketTime :hasPrePostMarketData :priceHint])
                       parse/kebabize-keys))))))
    (catch Exception e
      (parse/err {:type :exception :ticker ticker :message (.getMessage e)}))))

(defn fetch-info
  "Fetch basic ticker information and metadata. Returns map or nil on failure.
   
   Returns a map with available company info including:
   - :symbol, :long-name, :short-name
   - :currency, :exchange-name, :instrument-type
   - :regular-market-price, :regular-market-volume
   - :regular-market-day-high, :regular-market-day-low
   - :fifty-two-week-high, :fifty-two-week-low
   - :timezone, :gmt-offset
   
   Note: This is basic info from the chart endpoint. For comprehensive
   company info (P/E ratio, market cap, EPS, sector, industry, description),
   Yahoo's quoteSummary endpoint is required but currently blocked by
   authentication requirements. Consider using AlphaVantage or FMP for
   comprehensive fundamentals."
  [ticker]
  (let [result (fetch-info* ticker)]
    (when (:ok? result)
      (:data result))))

(comment
  ;; Usage examples

  (fetch-price "AAPL")

  (fetch-prices ["AAPL" "GOOGL" "MSFT" "0005.HK"])

  (fetch-prices ["AAPL" "GOOGL" "MSFT"] :concurrency 4)

  (take 2 (fetch-historical "AAPL" :period "1mo"))

  (fetch-historical "TSLA"
                    :start (.minus (java.time.Instant/now) (java.time.Duration/ofDays 30))
                    :interval "1h"
                    :prepost true)

  (fetch-dividends-splits "AAPL" :period "10y")

  (fetch-dividends-splits "MSFT"
                          :start (.minus (java.time.Instant/now) (java.time.Duration/ofDays 365))
                          :end (java.time.Instant/now))

  (fetch-info "AAPL"))
