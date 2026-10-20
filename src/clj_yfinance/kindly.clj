(ns clj-yfinance.kindly
  "Optional Kindly-tagged dataset functions for auto-rendering in Clay, Portal,
   and any Kindly-aware tool.

   REQUIRES: org.scicloj/kindly and techascent/tech.ml.dataset on the classpath.

   USAGE:
   (require '[clj-yfinance.kindly :as yfk])

   ;; Returns a TMD dataset tagged with kind/dataset — renders as an
   ;; interactive table in Clay, Portal, and other Kindly-aware tools
   (yfk/historical->dataset \"AAPL\" :period \"1mo\")
   (yfk/prices->dataset (yf/fetch-prices [\"AAPL\" \"GOOGL\" \"MSFT\"]))
   (yfk/multi-ticker->dataset [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")
   (yfk/dividends-splits->dataset \"AAPL\" :period \"10y\")
   (yfk/info->dataset \"AAPL\")

   Like clj-yfinance.dataset, every function returns nil when there is no data."
  (:require [clj-yfinance.dataset :as yfd]
            [scicloj.kindly.v4.kind :as kind]))

(defn- tag
  "Tag a dataset with kind/dataset, passing nil through (kind/dataset would
   wrap it as [nil])."
  [dataset]
  (some-> dataset kind/dataset))

(defn historical->dataset
  "Like clj-yfinance.dataset/historical->dataset but tagged with kind/dataset
   for auto-rendering in Kindly-aware tools."
  [ticker & opts]
  (tag (apply yfd/historical->dataset ticker opts)))

(defn prices->dataset
  "Like clj-yfinance.dataset/prices->dataset but tagged with kind/dataset."
  [prices-map]
  (tag (yfd/prices->dataset prices-map)))

(defn multi-ticker->dataset
  "Like clj-yfinance.dataset/multi-ticker->dataset but tagged with kind/dataset."
  [tickers & opts]
  (tag (apply yfd/multi-ticker->dataset tickers opts)))

(defn dividends-splits->dataset
  "Like clj-yfinance.dataset/dividends-splits->dataset but with each dataset
   tagged with kind/dataset."
  [ticker & opts]
  (some->> (apply yfd/dividends-splits->dataset ticker opts)
           (into {} (map (fn [[k dataset]] [k (tag dataset)])))))

(defn info->dataset
  "Like clj-yfinance.dataset/info->dataset but tagged with kind/dataset."
  [ticker]
  (tag (yfd/info->dataset ticker)))
