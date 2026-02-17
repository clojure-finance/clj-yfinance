(ns clj-yfinance.dataset
  "Optional dataset conversion functions for integration with the Clojure data science ecosystem.
   
   REQUIRES: Add tech.v3.dataset to your deps.edn:
   {:deps {tech.v3.dataset/tech.v3.dataset {:mvn/version \"8.003\"}}}
   
   WORKS AUTOMATICALLY WITH:
   - tech.v3.dataset (direct support - columnar data structure)
   - tablecloth (wrapper around tech.v3.dataset with friendly API)
   - noj (meta-library bundling tablecloth + tech.v3.dataset + visualization)
   - datajure (tidyverse-like syntax using tech.v3.dataset internally)
   
   FOR CLOJASK USERS:
   clojask uses a different paradigm (lazy parallel processing) and is not directly
   supported. To convert to clojask format:
   
   (require '[clojask.api :as ck])
   (def df (-> (yf/fetch-historical \"AAPL\" :period \"1mo\")
               (ck/dataframe)))  ; Convert plain maps to clojask
   
   EXAMPLES:
   
   ;; Basic dataset conversion
   (require '[clj-yfinance.dataset :as yfd])
   (yfd/historical->dataset \"AAPL\" :period \"1mo\")
   
   ;; Works with tablecloth
   (require '[tablecloth.api :as tc])
   (-> (yfd/historical->dataset \"AAPL\" :period \"1mo\")
       (tc/add-column :returns (fn [ds] ...))
       (tc/select-columns [:timestamp :close :returns]))
   
   ;; Works with noj notebooks
   (require '[scicloj.noj.v1.api :as noj])
   (noj/view (yfd/historical->dataset \"AAPL\" :period \"1mo\"))
   
   ;; Works with datajure (R-like syntax)
   (require '[datajure.dplyr :as dplyr])
   (-> (yfd/historical->dataset \"AAPL\" :period \"1mo\")
       (dplyr/mutate :returns '(/ close (lag close)))
       (dplyr/filter '(> returns 0.01)))
   
   ;; Multi-ticker analysis
   (yfd/multi-ticker->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")"
  (:require [clj-yfinance.core :as yf]
            [tech.v3.dataset :as ds]))

(defn historical->dataset
  "Convert historical OHLCV data to tech.v3.dataset with proper column types.
   
   Accepts either:
   - (ticker & opts) - fetches data then converts
   - (data-map) - converts already-fetched data
   
   Returns tech.v3.dataset with typed columns:
   - :timestamp (int64) - Unix epoch seconds
   - :open, :high, :low, :close, :adj-close (float64)
   - :volume (int64)
   
   Returns nil if data is empty or fetch fails.
   
   Examples:
   (historical->dataset \"AAPL\" :period \"1mo\")
   (historical->dataset \"TSLA\" :period \"1y\" :interval \"1d\")
   
   ;; Convert already-fetched data
   (def data (yf/fetch-historical \"AAPL\" :period \"1mo\"))
   (historical->dataset data)"
  ([data-or-ticker]
   (if (string? data-or-ticker)
     (when-let [data (yf/fetch-historical data-or-ticker)]
       (when (seq data)
         (ds/->dataset data
                       {:column-whitelist [:timestamp :open :high :low :close :volume :adj-close]
                        :parser-fn {:timestamp :int64
                                    :open :float64
                                    :high :float64
                                    :low :float64
                                    :close :float64
                                    :volume :int64
                                    :adj-close :float64}})))
     ;; Assume it's already-fetched data
     (when (seq data-or-ticker)
       (ds/->dataset data-or-ticker
                     {:column-whitelist [:timestamp :open :high :low :close :volume :adj-close]
                      :parser-fn {:timestamp :int64
                                  :open :float64
                                  :high :float64
                                  :low :float64
                                  :close :float64
                                  :volume :int64
                                  :adj-close :float64}}))))
  ([ticker & opts]
   (when-let [data (apply yf/fetch-historical ticker opts)]
     (when (seq data)
       (ds/->dataset data
                     {:column-whitelist [:timestamp :open :high :low :close :volume :adj-close]
                      :parser-fn {:timestamp :int64
                                  :open :float64
                                  :high :float64
                                  :low :float64
                                  :close :float64
                                  :volume :int64
                                  :adj-close :float64}})))))

(defn prices->dataset
  "Convert price map to dataset with ticker and price columns.
   
   Takes a map of {ticker price} (as returned by fetch-prices) and converts
   to a dataset with :ticker (string) and :price (float64) columns.
   
   Returns nil if prices map is empty.
   
   Example:
   (def prices (yf/fetch-prices [\"AAPL\" \"GOOGL\" \"MSFT\"]))
   (prices->dataset prices)
   ;; => tech.v3.dataset with columns [:ticker :price]"
  [prices-map]
  (when (seq prices-map)
    (ds/->dataset
     (map (fn [[ticker price]] {:ticker ticker :price price}) prices-map)
     {:parser-fn {:ticker :string :price :float64}})))

(defn dividends-splits->dataset
  "Convert dividends and splits data to separate datasets.
   
   Returns a map with :dividends and :splits keys, each containing a dataset.
   Returns nil if both dividends and splits are empty.
   
   Dividend columns: :timestamp (int64), :amount (float64)
   Split columns: :timestamp (int64), :numerator (int64), :denominator (int64)
   
   Example:
   (def data (yf/fetch-dividends-splits \"AAPL\" :period \"10y\"))
   (dividends-splits->dataset data)
   ;; => {:dividends #tech.v3.dataset<...>, :splits #tech.v3.dataset<...>}
   
   (dividends-splits->dataset \"AAPL\" :period \"5y\")"
  ([data-or-ticker]
   (if (string? data-or-ticker)
     (when-let [data (yf/fetch-dividends-splits data-or-ticker)]
       (dividends-splits->dataset data))
     ;; Assume it's already-fetched data
     (let [{:keys [dividends splits]} data-or-ticker
           div-ds (when (seq dividends)
                    (ds/->dataset
                     (map (fn [[ts m]] {:timestamp ts :amount (get m "amount")})
                          dividends)
                     {:parser-fn {:timestamp :int64 :amount :float64}}))
           split-ds (when (seq splits)
                      (ds/->dataset
                       (map (fn [[ts m]] {:timestamp ts
                                          :numerator (get m "numerator")
                                          :denominator (get m "denominator")})
                            splits)
                       {:parser-fn {:timestamp :int64
                                    :numerator :int64
                                    :denominator :int64}}))]
       (when (or div-ds split-ds)
         (cond-> {}
           div-ds (assoc :dividends div-ds)
           split-ds (assoc :splits split-ds))))))
  ([ticker & opts]
   (when-let [data (apply yf/fetch-dividends-splits ticker opts)]
     (dividends-splits->dataset data))))

(defn multi-ticker->dataset
  "Fetch historical data for multiple tickers and combine into single dataset.
   
   Returns a dataset with all tickers combined, including a :ticker column
   to identify each row's source ticker.
   
   Returns nil if all fetches fail or all data is empty.
   
   Columns: :ticker (string), :timestamp (int64), :open/:high/:low/:close/:adj-close (float64), :volume (int64)
   
   Example:
   (multi-ticker->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")
   (multi-ticker->dataset [\"AAPL\" \"TSLA\"] :period \"1mo\" :interval \"1h\")
   
   ;; Use with tablecloth for grouped operations
   (require '[tablecloth.api :as tc])
   (-> (multi-ticker->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")
       (tc/group-by [:ticker])
       (tc/add-column :returns (fn [ds] ...)))"
  [tickers & opts]
  (let [results (for [ticker tickers]
                  (when-let [data (apply yf/fetch-historical ticker opts)]
                    (map #(assoc % :ticker ticker) data)))]
    (when-let [combined (seq (apply concat (filter seq results)))]
      (ds/->dataset combined
                    {:parser-fn {:ticker :string
                                 :timestamp :int64
                                 :open :float64
                                 :high :float64
                                 :low :float64
                                 :close :float64
                                 :volume :int64
                                 :adj-close :float64}}))))

(defn info->dataset
  "Convert ticker info to single-row dataset.
   
   Returns a single-row dataset containing ticker metadata.
   Returns nil if fetch fails.
   
   Useful for combining multiple ticker info into a comparison table:
   
   Example:
   (info->dataset \"AAPL\")
   
   ;; Compare multiple tickers
   (require '[tech.v3.dataset :as ds])
   (ds/concat (map info->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"]))"
  ([data-or-ticker]
   (if (string? data-or-ticker)
     (when-let [data (yf/fetch-info data-or-ticker)]
       (ds/->dataset [data]))
     ;; Assume it's already-fetched data
     (when data-or-ticker
       (ds/->dataset [data-or-ticker]))))
  ([ticker & _opts]
   (when-let [data (yf/fetch-info ticker)]
     (ds/->dataset [data]))))
