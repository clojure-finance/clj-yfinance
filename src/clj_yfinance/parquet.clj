(ns clj-yfinance.parquet
  "Optional Parquet save/load functions for clj-yfinance datasets.

   REQUIRES: com.techascent/tmd-parquet and techascent/tech.ml.dataset on the classpath.

   USAGE:
   (require '[clj-yfinance.parquet :as yfp])

   ;; Save and load historical data
   (yfp/save-historical! \"AAPL\" \"aapl.parquet\" :period \"5y\")
   (yfp/load-historical \"aapl.parquet\")

   ;; Save a multi-ticker dataset in one file
   (yfp/save-multi-ticker! [\"AAPL\" \"GOOGL\" \"MSFT\"] \"prices.parquet\" :period \"1y\")
   (yfp/load-dataset \"prices.parquet\")"
  (:require [clj-yfinance.dataset :as yfd]
            [tech.v3.dataset :as ds]
            [tech.v3.libs.parquet :as parquet]))

(defn- keywordize-columns [dataset]
  (let [cols (ds/column-names dataset)]
    (ds/rename-columns dataset (zipmap cols (map keyword cols)))))

(defn save-historical!
  "Fetch historical OHLCV data for ticker and save to a Parquet file.

   Returns the dataset on success, nil if fetch fails or data is empty.

   Options are passed through to fetch-historical:
   :period, :interval, :start, :end, :auto-adjust, :prepost

   Examples:
   (save-historical! \"AAPL\" \"aapl.parquet\" :period \"5y\")
   (save-historical! \"TSLA\" \"tsla-1h.parquet\" :start start-inst :end end-inst :interval \"1h\")"
  [ticker path & opts]
  (when-let [ds (apply yfd/historical->dataset ticker opts)]
    (parquet/ds->parquet ds path)
    ds))

(defn save-multi-ticker!
  "Fetch historical OHLCV data for multiple tickers and save to a single Parquet file.

   The dataset includes a :ticker column to identify each row's source.
   Returns the combined dataset on success, nil if all fetches fail.

   Options are passed through to fetch-historical:
   :period, :interval, :start, :end, :auto-adjust, :prepost

   Examples:
   (save-multi-ticker! [\"AAPL\" \"GOOGL\" \"MSFT\"] \"tech.parquet\" :period \"1y\")
   (save-multi-ticker! tickers \"portfolio.parquet\" :period \"5y\")"
  [tickers path & opts]
  (when-let [ds (apply yfd/multi-ticker->dataset tickers opts)]
    (parquet/ds->parquet ds path)
    ds))

(defn load-historical
  "Load a Parquet file saved by save-historical! and return a dataset.

   Column names are keywordized on load. Column types are restored as saved:
   :timestamp (int64), OHLCV as float64/int64.

   Example:
   (load-historical \"aapl.parquet\")"
  [path]
  (keywordize-columns (parquet/parquet->ds path)))

(defn load-dataset
  "Load any Parquet file and return a dataset. Column names are keywordized on load.

   Example:
   (load-dataset \"prices.parquet\")"
  [path]
  (keywordize-columns (parquet/parquet->ds path)))

(defn save-dataset!
  "Save an arbitrary tech.v3.dataset to a Parquet file.

   Use this when you have already transformed a dataset and want to persist it.

   Example:
   (require '[tablecloth.api :as tc])
   (-> (yfd/historical->dataset \"AAPL\" :period \"1y\")
       (tc/add-column :log-return ...)
       (yfp/save-dataset! \"aapl-enriched.parquet\"))"
  [ds path]
  (parquet/ds->parquet ds path)
  ds)
