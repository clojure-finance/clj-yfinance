(ns clj-yfinance.dataset
  "Optional dataset conversion functions for integration with the Clojure data science ecosystem.

   REQUIRES: techascent/tech.ml.dataset on the classpath.

   The results are plain tech.v3.dataset datasets, so they work directly with
   tablecloth, Noj, datajure and anything else built on tech.ml.dataset.

   EXAMPLES:

   (require '[clj-yfinance.dataset :as yfd])
   (yfd/historical->dataset \"AAPL\" :period \"1mo\")
   (yfd/multi-ticker->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")

   ;; With tablecloth
   (require '[tablecloth.api :as tc])
   (-> (yfd/historical->dataset \"AAPL\" :period \"1mo\")
       (tc/add-column :returns (fn [ds] ...))
       (tc/select-columns [:timestamp :close :returns]))"
  (:require [clj-yfinance.core :as yf]
            [tech.v3.dataset :as ds]))

(def ^:private ohlcv-types
  {:timestamp :int64
   :open :float64
   :high :float64
   :low :float64
   :close :float64
   :volume :int64
   :adj-close :float64})

(defn- rows->dataset
  "Typed dataset from OHLCV row maps, or nil when there are no rows."
  [rows parser-fn]
  (when (seq rows)
    (ds/->dataset rows {:parser-fn parser-fn})))

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
   (rows->dataset (if (string? data-or-ticker)
                    (yf/fetch-historical data-or-ticker)
                    data-or-ticker)
                  ohlcv-types))
  ([ticker & opts]
   (rows->dataset (apply yf/fetch-historical ticker opts) ohlcv-types)))

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
  (rows->dataset (map (fn [[ticker price]] {:ticker ticker :price price}) prices-map)
                 {:ticker :string :price :float64}))

(defn dividends-splits->dataset
  "Convert dividends and splits data to separate datasets.

   Returns a map with :dividends and :splits keys, each containing a dataset;
   a key is absent when there are no events of that kind. Returns nil if both
   are empty. Timestamps come from each event's :date; rows are in date order.

   Dividend columns: :timestamp (int64), :amount (float64)
   Split columns: :timestamp (int64), :numerator (float64), :denominator (float64)

   Example:
   (def data (yf/fetch-dividends-splits \"AAPL\" :period \"10y\"))
   (dividends-splits->dataset data)
   ;; => {:dividends #tech.v3.dataset<...>, :splits #tech.v3.dataset<...>}

   (dividends-splits->dataset \"AAPL\" :period \"5y\")"
  ([data-or-ticker]
   (if (string? data-or-ticker)
     (dividends-splits->dataset (yf/fetch-dividends-splits data-or-ticker))
     (let [{:keys [dividends splits]} data-or-ticker
           div-ds (rows->dataset (map (fn [m] {:timestamp (:date m) :amount (:amount m)})
                                      (sort-by :date (vals dividends)))
                                 {:timestamp :int64 :amount :float64})
           split-ds (rows->dataset (map (fn [m] {:timestamp (:date m)
                                                 :numerator (:numerator m)
                                                 :denominator (:denominator m)})
                                        (sort-by :date (vals splits)))
                                   {:timestamp :int64 :numerator :float64 :denominator :float64})]
       (when (or div-ds split-ds)
         (cond-> {}
           div-ds (assoc :dividends div-ds)
           split-ds (assoc :splits split-ds))))))
  ([ticker & opts]
   (dividends-splits->dataset (apply yf/fetch-dividends-splits ticker opts))))

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
  (rows->dataset (mapcat (fn [ticker]
                           (map #(assoc % :ticker ticker)
                                (apply yf/fetch-historical ticker opts)))
                         tickers)
                 (assoc ohlcv-types :ticker :string)))

(defn info->dataset
  "Convert ticker info to single-row dataset.

   Returns a single-row dataset containing ticker metadata.
   Returns nil if fetch fails.

   Useful for combining multiple ticker info into a comparison table:

   Example:
   (info->dataset \"AAPL\")

   ;; Compare multiple tickers
   (require '[tech.v3.dataset :as ds])
   (apply ds/concat (map info->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"]))"
  ([data-or-ticker]
   (if (string? data-or-ticker)
     (info->dataset (yf/fetch-info data-or-ticker))
     (when data-or-ticker
       (ds/->dataset [data-or-ticker]))))
  ([ticker & _opts]
   (info->dataset ticker)))
