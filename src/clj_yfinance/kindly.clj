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
   (yfk/info->dataset \"AAPL\")"
  (:require [clj-yfinance.dataset :as yfd]
            [scicloj.kindly.v4.kind :as kind]))

(defn historical->dataset
  "Like clj-yfinance.dataset/historical->dataset but tagged with kind/dataset
   for auto-rendering in Kindly-aware tools."
  [ticker & opts]
  (kind/dataset (apply yfd/historical->dataset ticker opts)))

(defn prices->dataset
  "Like clj-yfinance.dataset/prices->dataset but tagged with kind/dataset."
  [prices-map]
  (kind/dataset (yfd/prices->dataset prices-map)))

(defn multi-ticker->dataset
  "Like clj-yfinance.dataset/multi-ticker->dataset but tagged with kind/dataset."
  [tickers & opts]
  (kind/dataset (apply yfd/multi-ticker->dataset tickers opts)))

(defn dividends-splits->dataset
  "Like clj-yfinance.dataset/dividends-splits->dataset but returns both datasets
   tagged with kind/dataset."
  [ticker & opts]
  (let [result (apply yfd/dividends-splits->dataset ticker opts)]
    {:dividends (kind/dataset (:dividends result))
     :splits (kind/dataset (:splits result))}))

(defn info->dataset
  "Like clj-yfinance.dataset/info->dataset but tagged with kind/dataset."
  [ticker]
  (kind/dataset (yfd/info->dataset ticker)))
