(ns clj-yfinance.validation
  "Input validation and range checking for Yahoo Finance parameters."
  (:require [clj-yfinance.parse :refer [->epoch ok err]]
            [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private interval-max-range
  {"1m" 7, "2m" 60, "5m" 60, "15m" 60, "30m" 60,
   "60m" 730, "90m" 60, "1h" 730,
   "1d" ##Inf, "5d" ##Inf, "1wk" ##Inf, "1mo" ##Inf, "3mo" ##Inf})

(def ^:private range-days
  {"1d" 1, "5d" 5, "1mo" 30, "3mo" 90, "6mo" 180, "1y" 365, "2y" 730,
   "5y" 1825, "10y" 3650, "ytd" 365, "max" ##Inf})

(def ^:private allowed-periods
  #{"1d" "5d" "1mo" "3mo" "6mo" "1y" "2y" "5y" "10y" "ytd" "max"})

(def ^:private allowed-intervals
  #{"1m" "2m" "5m" "15m" "30m" "60m" "90m" "1h" "1d" "5d" "1wk" "1mo" "3mo"})

(defn validate-opts
  "Validate chart options, returning {:ok? true} or {:ok? false :error {...}}."
  [{:keys [period interval start end]}]
  (letfn [(valid-time? [x]
            (or (nil? x) (integer? x) (instance? java.time.Instant x)))]
    (cond
      (and end (not start))
      (err {:type :invalid-opts
            :field :end
            :value end
            :message ":end requires :start. Provide both, or use :period."})

      (and (nil? start) (nil? period))
      (err {:type :invalid-opts
            :field :period
            :value period
            :message "Provide :period (range) or :start (with optional :end)."})

      (not (valid-time? start))
      (err {:type :invalid-opts
            :field :start
            :value start
            :message "start must be epoch seconds (integer) or java.time.Instant"})

      (not (valid-time? end))
      (err {:type :invalid-opts
            :field :end
            :value end
            :message "end must be epoch seconds (integer) or java.time.Instant"})

      (and period (not (allowed-periods period)))
      (err {:type :invalid-opts
            :field :period
            :value period
            :allowed allowed-periods
            :message (str "Invalid period: " period ". Must be one of: " (str/join ", " (sort allowed-periods)))})

      (and interval (not (allowed-intervals interval)))
      (err {:type :invalid-opts
            :field :interval
            :value interval
            :allowed allowed-intervals
            :message (str "Invalid interval: " interval ". Must be one of: " (str/join ", " (sort allowed-intervals)))})

      (and start end (> (->epoch start) (->epoch end)))
      (err {:type :invalid-opts
            :field :start/end
            :message "start must be <= end"
            :start start
            :end end})

      :else
      (ok nil))))

(defn interval-range-warnings
  "Returns a vector of warning maps if interval may be invalid for given range. Returns empty vector if valid."
  [{:keys [interval period start end]}]
  (let [max-days (get interval-max-range interval ##Inf)
        req-days (if start
                   (/ (- (->epoch (or end (Instant/now)))
                         (->epoch start))
                      86400)
                   (get range-days period 365))]
    (if (> req-days max-days)
      [{:type :interval-too-wide
        :interval interval
        :max-days max-days
        :requested-days (int req-days)
        :message (str interval " limited to " max-days " days; requested ~" (int req-days))}]
      [])))

(defn chart-time-params
  "Build time-related query parameters from period or start/end."
  [{:keys [period start end]}]
  (cond-> {}
    (not start) (assoc :range period)
    start (assoc :period1 (->epoch start))
    (or end start) (assoc :period2 (->epoch (or end (Instant/now))))))
