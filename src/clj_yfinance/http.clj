(ns clj-yfinance.http
  "HTTP client, retry logic, and request/response handling for Yahoo Finance API."
  (:require [clj-yfinance.parse :refer [ok err parse-chart-body encode-path-segment]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers]
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
    (ok (.send ^HttpClient client ^HttpRequest request (HttpResponse$BodyHandlers/ofString)))
    (catch Exception ex
      (err {:type :connection-error
            :ticker ticker
            :url url
            :message (.getMessage ex)}))))

(defn- chart-response
  "Response parser for the chart endpoint: 200 bodies go to parse-chart-body,
   any other status is an :http-error."
  [status body]
  (if (= 200 status)
    (parse-chart-body body)
    (err {:type :http-error})))

(defn- parse-response*
  "Turn an HttpResponse into {:ok? true :data ...} or {:ok? false :error ...}.
   429 is always :rate-limited; every other status and its body go to
   `parse-fn` (fn [status body] -> result). :ticker, :status and :url are added
   to any error it returns."
  [^HttpResponse response {:keys [ticker url parse-fn]}]
  (let [status (.statusCode response)]
    (if (= 429 status)
      (err {:type :rate-limited :status status :ticker ticker :url url})
      (let [result (parse-fn status (.body response))]
        (if (:ok? result)
          result
          (update result :error assoc :ticker ticker :status status :url url))))))

(defn get-with-failover*
  "GET each URL in `urls` in turn until one succeeds. Each URL gets up to 3
   attempts with exponential backoff (250ms base, plus jitter) for transient
   failures (429, connection errors, 5xx). Returns the first success, or the
   last failure.

   Options:
     :ticker   - added to error maps
     :parse-fn - (fn [status body] -> {:ok? ...}), called for every status but
                 429; must return an error for statuses it does not accept"
  [urls {:keys [ticker parse-fn]}]
  (loop [[url & remaining] urls
         last-error nil]
    (if-not url
      (or last-error (err {:type :no-response :ticker ticker}))
      (let [request (build-request url)
            result (with-retries
                     (fn []
                       (let [http-result (send-request* @http-client request {:ticker ticker :url url})]
                         (if-not (:ok? http-result)
                           http-result
                           (parse-response* (:data http-result)
                                            {:ticker ticker :url url :parse-fn parse-fn}))))
                     {:max-attempts 3 :base-delay-ms 250})]
        (if (:ok? result)
          result
          (recur remaining result))))))

(defn try-fetch*
  "Try fetching from primary URL, fallback to secondary on failure. Includes retry with backoff for transient errors."
  [ticker query-params]
  (get-with-failover*
   (mapv (fn [base-url]
           (str base-url (encode-path-segment ticker)
                (when (seq query-params) (str "?" query-params))))
         base-urls)
   {:ticker ticker :parse-fn chart-response}))
