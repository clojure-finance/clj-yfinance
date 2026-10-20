(ns finance-demo
  "clj-yfinance demo notebook — fetch, transform, and visualise financial data.

   Start with: clojure -M:clay:nrepl
   Render in Clay: (clay/make! {:source-path \"examples/finance_demo.clj\"})"
  (:require [clj-yfinance.core :as yf]
            [clj-yfinance.dataset :as yfd]
            [scicloj.clay.v2.api :as clay]
            [scicloj.kindly.v4.kind :as kind]
            [scicloj.tableplot.v1.plotly :as plotly]
            [tablecloth.api :as tc]
            [tech.v3.dataset :as ds]
            [tech.v3.datatype.functional :as dfn]))

;; # clj-yfinance Demo
;;
;; A live notebook showing how to fetch, transform, and visualise financial
;; data using clj-yfinance + tablecloth + tableplot inside a Clay notebook.

;; ## 1. Current Prices
;;
;; Fetch the latest price for a set of tickers in parallel.

(def tickers ["AAPL" "GOOGL" "MSFT" "TSLA" "NVDA"])

(def prices (yf/fetch-prices tickers))

prices

;; Convert to a dataset for display.

(def prices-ds (yfd/prices->dataset prices))

prices-ds

;; ## 2. Historical OHLCV Data
;;
;; Fetch one year of daily bars for Apple.

(def aapl (yfd/historical->dataset "AAPL" :period "1y"))

aapl

;; ## 3. Tablecloth Transformations
;;
;; Add a daily log-return column and a calendar-date column (used by all the
;; charts below).

(defn add-date
  "Add a :date column (ISO date string, UTC) derived from :timestamp."
  [ds]
  (tc/add-column ds :date (fn [ds]
                            (map #(str (java.time.LocalDate/ofEpochDay (quot % 86400)))
                                 (ds :timestamp)))))

(defn log-returns [ds]
  (let [c (-> ds (tc/order-by [:timestamp]) (tc/column :close))]
    (into [] (map (fn [a b] (Math/log (/ b a))) c (rest c)))))

(def aapl-enriched
  (-> aapl
      (tc/order-by [:timestamp])
      (tc/drop-rows 0)
      (tc/add-column :log-return (log-returns aapl))
      add-date))

(tc/select-columns aapl-enriched [:date :close :log-return])

;; ## 4. Multi-Ticker Dataset
;;
;; Fetch 1 year of data for all five tickers and combine into one dataset.

(def multi-ds
  (yfd/multi-ticker->dataset tickers :period "1y"))

(tc/group-by multi-ds [:ticker] {:result-type :as-map})

;; ## 5. Price Chart
;;
;; Plot closing prices for all tickers over time.

(-> multi-ds
    add-date
    (plotly/base {:=title "Closing Prices — 1 Year"})
    (plotly/layer-line {:=x :date
                        :=y :close
                        :=color :ticker
                        :=mark-size 1}))

;; ## 6. Normalised Performance Chart
;;
;; Normalise each ticker to 100 at the start of the period for comparison.

(def normalised-ds
  (-> multi-ds
      (tc/order-by [:ticker :timestamp])
      (tc/group-by [:ticker])
      (tc/add-column :indexed-close
                     (fn [ds]
                       (let [c (ds :close)]
                         (dfn/* (dfn// c (first c)) 100.0))))
      tc/ungroup
      add-date))

(-> normalised-ds
    (plotly/base {:=title "Normalised Performance (base = 100)"})
    (plotly/layer-line {:=x :date
                        :=y :indexed-close
                        :=color :ticker
                        :=mark-size 1}))

;; ## 7. Log-Returns Chart
;;
;; Plot daily log-returns for all tickers to see which days had big moves.

(def multi-enriched
  (-> multi-ds
      (tc/order-by [:ticker :timestamp])
      (tc/group-by [:ticker])
      (tc/add-column :log-return
                     (fn [ds]
                       (let [c (tc/column ds :close)]
                         (into [0.0]
                               (map (fn [a b] (Math/log (/ b a))) c (rest c))))))
      tc/ungroup
      add-date))

(-> multi-enriched
    (tc/drop-rows (fn [row] (zero? (:log-return row))))
    (plotly/base {:=title "Daily Log-Returns"})
    (plotly/layer-line {:=x :date
                        :=y :log-return
                        :=color :ticker
                        :=mark-size 1}))

;; ## 8. 20-Day Rolling Volatility
;;
;; Rolling standard deviation of log-returns, annualised (× √252).

(defn rolling-std [xs window]
  (let [v (vec xs)]
    (map-indexed
     (fn [i _]
       (if (< i window)
         nil
         (let [window-vals (subvec v (- i window) i)
               n (count window-vals)
               mean (/ (reduce + window-vals) n)
               variance (/ (reduce + (map #(Math/pow (- % mean) 2) window-vals)) (dec n))]
           (* (Math/sqrt variance) (Math/sqrt 252)))))
     v)))

(def volatility-ds
  (-> multi-enriched
      (tc/group-by [:ticker])
      (tc/add-column :vol20
                     (fn [ds]
                       (into [] (rolling-std (tc/column ds :log-return) 20))))
      tc/ungroup
      (tc/drop-missing [:vol20])))

(-> volatility-ds
    (plotly/base {:=title "20-Day Rolling Volatility (Annualised)"})
    (plotly/layer-line {:=x :date
                        :=y :vol20
                        :=color :ticker
                        :=mark-size 1}))

;; ## 9. Returns Distribution
;;
;; Density plot of log-returns per ticker — shows fat tails and skew.

(-> multi-enriched
    (tc/drop-rows (fn [row] (zero? (:log-return row))))
    (plotly/base {:=title "Distribution of Daily Log-Returns"})
    (plotly/layer-density {:=x :log-return
                           :=color :ticker}))

;; ## 10. Dividends & Splits
;;
;; Fetch Apple's dividend history as a dataset.

(def aapl-divs
  (-> (yfd/dividends-splits->dataset "AAPL" :period "10y")
      :dividends
      add-date))

aapl-divs

;; ## 11. Ticker Info
;;
;; Fetch metadata for all tickers and display as a comparison table.

(def info-ds
  (-> (apply ds/concat-copying (map yfd/info->dataset tickers))
      (tc/select-columns [:symbol :long-name :regular-market-price
                          :fifty-two-week-high :fifty-two-week-low
                          :regular-market-volume])))

info-ds

(comment
  (clay/make! {:source-path "examples/finance_demo.clj"}))
