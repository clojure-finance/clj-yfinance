(ns clj-yfinance.http
  "HTTP client, retry logic, and request/response handling for Yahoo Finance API."
  (:require [clj-yfinance.parse :refer [ok err parse-chart-body encode-path-segment build-query]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private http-client
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (Duration/ofSeconds 10))
        (.build))))

(def ^:private base-urls
  ["https://query1.finance.yahoo.com/v8/finance/chart/"
   "https://query2.finance.yahoo.com/v8/finance/chart/"])

(defn- build-request
  "Build HTTP request with per-request timeout."
  [url]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create url))
      (.timeout (Duration/ofSeconds 15))
      (.header "User-Agent" "Mozilla/5.0")
      (.header "Accept" "application/json")
      (.GET)
      (.build)))

(defn- retryable?
  "Check if an error is retryable (transient failures)."
  [result]
  (and (not (:ok? result))
       (let [{:keys [type status]} (:error result)]
         (or (= type :connection-error)
             (= type :rate-limited)
             (and (= type :http-error) (<= 500 (or status 0) 599))))))

(defn- with-retries
  "Retry a thunk with exponential backoff for transient failures."
  [thunk {:keys [max-attempts base-delay-ms]
          :or {max-attempts 3 base-delay-ms 250}}]
  (loop [attempt 1 delay base-delay-ms]
    (let [result (thunk)]
      (if (and (< attempt max-attempts) (retryable? result))
        (do (Thread/sleep (+ delay (rand-int 100)))
            (recur (inc attempt) (* 2 delay)))
        result))))

(defn- send-request*
  "Send HTTP request, returning {:ok? true :data HttpResponse} or {:ok? false :error ...}."
  [client request {:keys [ticker url]}]
  (try
    (ok (.send client request (HttpResponse$BodyHandlers/ofString)))
    (catch Exception ex
      (err {:type :connection-error
            :ticker ticker
            :url url
            :message (.getMessage ex)}))))

(defn- parse-response*
  "Parse response, returning structured result {:ok? true :data ...} or {:ok? false :error ...}."
  [response {:keys [ticker url]}]
  (let [status (.statusCode response)]
    (cond
      (= 429 status)
      (err {:type :rate-limited :status status :ticker ticker :url url})

      (not= 200 status)
      (err {:type :http-error :status status :ticker ticker :url url})

      :else
      (let [parse-result (parse-chart-body (.body response))]
        (if (:ok? parse-result)
          parse-result
          (update parse-result :error assoc :ticker ticker :status status :url url))))))

(defn try-fetch*
  "Try fetching from primary URL, fallback to secondary on failure. Includes retry with backoff for transient errors."
  [ticker query-params]
  (loop [[base-url & remaining] base-urls
         last-error nil]
    (if-not base-url
      (or last-error (err {:type :no-response :ticker ticker}))
      (let [url (str base-url (encode-path-segment ticker)
                     (when (seq query-params) (str "?" query-params)))
            request (build-request url)
            result (with-retries
                     (fn []
                       (let [http-result (send-request* @http-client request {:ticker ticker :url url})]
                         (if-not (:ok? http-result)
                           http-result
                           (parse-response* (:data http-result) {:ticker ticker :url url}))))
                     {:max-attempts 3 :base-delay-ms 250})]
        (if (:ok? result)
          result
          (recur remaining result))))))
