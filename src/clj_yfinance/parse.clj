(ns clj-yfinance.parse
  "JSON parsing and data transformation utilities."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.time Instant]))

(defn ->epoch
  "Convert Instant or long to epoch seconds. Returns nil for nil input."
  [x]
  (cond
    (nil? x) nil
    (instance? Instant x) (.getEpochSecond ^Instant x)
    (integer? x) x
    :else (throw (ex-info "Invalid timestamp" {:value x}))))

(defn encode-path-segment
  "URL-encode a path segment (e.g., ticker symbol)."
  [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn ok
  "Construct a success result."
  [data]
  {:ok? true :data data})

(defn err
  "Construct an error result."
  [error-map]
  {:ok? false :error error-map})

(defn build-query
  "Build URL query string from a map, removing nil values and encoding keys/values."
  [m]
  (->> m
       (remove (comp nil? val))
       (map (fn [[k v]] (str (encode-path-segment (name k)) "=" (encode-path-segment v))))
       (str/join "&")))

(defn parse-chart-body
  "Parse JSON response body into chart data or error. Pure function for testing."
  [json-string]
  (try
    (let [body (json/parse-string json-string true)]
      (if-let [e (-> body :chart :error)]
        (err {:type :api-error :message (:description e)})
        (let [result (-> body :chart :result)]
          (cond
            (nil? result)
            (err {:type :api-error :message "No result field in response"})

            (empty? result)
            (err {:type :no-data :message "Empty result array from API"})

            :else
            (ok (first result))))))
    (catch Exception ex
      (err {:type :parse-error :message (.getMessage ex)}))))

(def ^:private key-exceptions
  {"gmtoffset" "gmt-offset"})

(defn camel->kebab
  "Convert camelCase key to kebab-case. Handles special cases via key-exceptions map."
  [k]
  (let [s (name k)
        s (or (key-exceptions s)
              (-> s
                  (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                  (str/lower-case)))]
    (keyword s)))

(defn kebabize-keys
  "Convert all keys in map from camelCase to kebab-case."
  [m]
  (into {} (map (fn [[k v]] [(camel->kebab k) v]) m)))
