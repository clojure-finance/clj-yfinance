(ns clj-yfinance.experimental.earnings
  "EXPERIMENTAL: earnings report dates and EPS surprises.

   - fetch-earnings-history / * - the last four quarters from the quoteSummary
     `earnings` module: fiscal period end paired with the date and time the
     results were reported, EPS actual vs. estimate.
   - fetch-earnings-dates / *   - the longer history of earnings announcement
     events from Yahoo's visualization API (back to the 1990s for large US
     companies), with EPS estimate, actual and surprise.

   Both use the authenticated session of clj-yfinance.experimental.auth.

   Data notes (Yahoo's behaviour, not this library's):
   - EPS here is the figure analysts estimate (usually adjusted, non-GAAP),
     which can differ from the reported diluted EPS of the statements.
   - :period-end is normalised to the month end, as in the statements.
   - The visualization history currently stops in mid-2025 (Yahoo no longer
     seems to update it); fetch-earnings-history covers the recent quarters.

   WARNING: Yahoo does not document these endpoints and can change them at any
   time."
  (:require [clj-yfinance.experimental.auth :as auth]
            [clj-yfinance.experimental.fundamentals :as fundamentals]
            [charred.api :as charred]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- ->double
  "Number or numeric string as a finite double; anything else nil."
  [x]
  (let [d (cond
            (number? x) (double x)
            (string? x) (try (Double/parseDouble (str/trim x))
                             (catch NumberFormatException _ nil)))]
    (when (and d (Double/isFinite d)) d)))

(defn- ->long [x]
  (when (number? x) (long x)))

(defn- invalid [field value message]
  {:ok? false :error {:type :invalid-opts :field field :value value :message message}})

(defn- ticker-error [ticker]
  (when-not (and (string? ticker) (not (str/blank? ticker)))
    (invalid :ticker ticker "ticker must be a non-blank string")))

;; ---------------------------------------------------------------------------
;; Earnings history (quoteSummary `earnings` module)
;; ---------------------------------------------------------------------------

(defn earnings-history-rows
  "Rows from the quoteSummary `earnings` module map (the value of :earnings),
   sorted by period end. Strings are parsed to doubles; absent values are nil.
   Pure function for testing."
  [ticker earnings]
  (let [currency (:financialCurrency earnings)]
    (->> (-> earnings :earningsChart :quarterly)
         (filter map?)
         (map (fn [q]
                {:ticker ticker
                 :period-end (-> q :periodEndDate :fmt)
                 :reported-date (-> q :reportedDate :fmt)
                 :reported-at (->long (-> q :reportedDate :raw))
                 :fiscal-quarter (:fiscalQuarter q)
                 :calendar-quarter (:calendarQuarter q)
                 :eps-actual (->double (-> q :actual :raw))
                 :eps-estimate (->double (-> q :estimate :raw))
                 :eps-difference (->double (:difference q))
                 :surprise-pct (->double (:surprisePct q))
                 :currency currency}))
         (sort-by (juxt (comp nil? :period-end) :period-end))
         vec)))

(defn fetch-earnings-history*
  "Fetch the last four reported quarters for a ticker (verbose API), from the
   quoteSummary `earnings` module.

   Returns {:ok? true :data [rows] :request {...}} or {:ok? false :error {...}}
   (error types as for fundamentals/fetch-quotesummary*). Rows, sorted by
   period end:
     {:ticker \"AAPL\" :period-end \"2025-09-30\" :reported-date \"2025-10-30\"
      :reported-at 1761856200 :fiscal-quarter \"4Q2025\"
      :calendar-quarter \"3Q2025\" :eps-actual 1.85 :eps-estimate 1.76993
      :eps-difference 0.08 :surprise-pct 4.52 :currency \"USD\"}
   :reported-at is epoch seconds of the release (e.g. 16:30 New York time for
   an after-the-close report); :reported-date is Yahoo's date string for it.
   :currency is the reporting currency of the EPS figures. Values Yahoo leaves
   out are nil. A ticker without the module's quarterly data gives []."
  [ticker]
  (or (ticker-error ticker)
      (let [request {:ticker ticker :modules "earnings"}
            result (fundamentals/fetch-quotesummary* ticker "earnings")]
        (if (:ok? result)
          (assoc result
                 :data (earnings-history-rows ticker (-> result :data :earnings))
                 :request request)
          (assoc result :request request)))))

(defn fetch-earnings-history
  "Fetch the last four reported quarters for a ticker (simple API). Returns
   the rows of fetch-earnings-history*, or [] on failure."
  [ticker]
  (let [result (fetch-earnings-history* ticker)]
    (if (:ok? result) (:data result) [])))

;; ---------------------------------------------------------------------------
;; Earnings dates (visualization API)
;; ---------------------------------------------------------------------------

(def ^:private visualization-url "https://query1.finance.yahoo.com/v1/finance/visualization")

(def ^:private max-page-size
  "Largest `size` Yahoo accepts (251 gives 400 \"size is too large\")."
  250)

(def ^:private include-fields
  ["ticker" "companyshortname" "eventname" "startdatetime" "startdatetimetype"
   "epsestimate" "epsactual" "epssurprisepct" "timeZoneShortName"
   "gmtOffsetMilliSeconds"])

(defn- visualization-body [ticker offset size]
  (charred/write-json-str
   {"sortType" "DESC"
    "entityIdType" "earnings"
    "sortField" "startdatetime"
    "includeFields" include-fields
    "query" {"operator" "eq" "operands" ["ticker" ticker]}
    "offset" offset
    "size" size}))

(defn- crumb-url [crumb]
  (str visualization-url "?crumb=" (URLEncoder/encode (str crumb) "UTF-8")
       "&lang=en-US&region=US"))

(defn- iso-instant
  "\"2025-05-01T20:30:00.000Z\" -> Instant, nil when absent or unparseable."
  ^Instant [s]
  (when (string? s)
    (try (Instant/parse s) (catch Exception _ nil))))

(defn- event-row [ticker m]
  (let [instant (iso-instant (get m "startdatetime"))
        event-name (some-> (get m "eventname") str str/trim)]
    {:ticker (or (get m "ticker") ticker)
     :company-name (get m "companyshortname")
     :event-name (when-not (str/blank? event-name) event-name)
     :announced-at (some-> instant str)
     :announced-epoch (some-> instant .getEpochSecond)
     :timing (get m "startdatetimetype")
     :eps-estimate (->double (get m "epsestimate"))
     :eps-actual (->double (get m "epsactual"))
     :surprise-pct (->double (get m "epssurprisepct"))
     :timezone (get m "timeZoneShortName")
     :gmt-offset-ms (->long (get m "gmtOffsetMilliSeconds"))}))

(defn- yahoo-error [body]
  (try (-> (charred/read-json body {:key-fn keyword}) :finance :error)
       (catch Exception _ nil)))

(defn parse-visualization-response
  "Interpret a visualization API status and body. Success is
   {:ok? true :data {:rows [...] :total n}} with rows in response order,
   columns mapped by their id (not their position). Pure function for testing."
  [ticker status body]
  (let [fail (fn [type message & {:as extra}]
               {:ok? false :error (merge {:type type :message message :ticker ticker :status status} extra)})]
    (case status
      200
      (try
        (let [{:keys [result error]} (:finance (charred/read-json body {:key-fn keyword}))
              {:keys [total documents]} (first result)
              {:keys [columns rows]} (first documents)
              ids (mapv :id columns)]
          (cond
            error (fail :api-error (str "Yahoo API error: " (:description error)) :yahoo-error error)
            (nil? (first result)) (fail :missing-data "No visualization result in response")
            :else {:ok? true
                   :data {:total total
                          :rows (mapv #(event-row ticker (zipmap ids %)) rows)}}))
        (catch Exception e
          (fail :parse-error (str "Failed to parse JSON response: " (.getMessage e)))))

      401 (fail :auth-failed "Authentication failed after retry")
      429 (fail :rate-limited "Rate limit exceeded")
      (let [e (yahoo-error body)]
        (fail :http-error (str "HTTP error " status (when e (str ": " (:description e))))
              :yahoo-error e)))))

(defn- fetch-page [ticker offset size]
  (let [result (auth/authenticated-request crumb-url
                                           :method :post
                                           :body (visualization-body ticker offset size))]
    (if (:ok? result)
      (let [response ^java.net.http.HttpResponse (:response result)]
        (parse-visualization-response ticker (.statusCode response) (.body response)))
      {:ok? false :error (assoc (:error result) :ticker ticker)})))

(defn fetch-earnings-dates*
  "Fetch earnings announcement events for a ticker (verbose API), from Yahoo's
   visualization API.

   Options:
     :limit - how many of the most recent events to fetch (default 100). Yahoo
              serves at most 250 per request; larger limits are paged.

   Returns {:ok? true :data [rows] :request {...}} or {:ok? false :error {...}}
   (:auth-failed, :session-failed, :request-failed, :rate-limited,
   :http-error, :api-error, :missing-data, :parse-error, :invalid-opts). Rows,
   sorted ascending by time:
     {:ticker \"AAPL\" :company-name \"Apple Inc.\" :event-name nil
      :announced-at \"2025-05-01T20:30:00Z\" :announced-epoch 1746131400
      :timing \"TAS\" :eps-estimate 1.63 :eps-actual 1.65 :surprise-pct 1.41
      :timezone \"EDT\" :gmt-offset-ms -14400000}
   :event-name is nil for plain earnings releases and names other events
   (e.g. \"Annual Shareholders Meeting\", \"Q1 2025 Earnings Call\"), whose
   EPS fields are usually nil. :timezone and :gmt-offset-ms are the exchange's
   at the event. Values Yahoo leaves out are nil. If any page fails, the whole
   call fails."
  [ticker & {:keys [limit] :or {limit 100} :as opts}]
  (or (ticker-error ticker)
      (when-let [unknown (seq (remove #{:limit} (keys opts)))]
        (invalid :opts (vec unknown) (str "Unknown option(s): " (str/join ", " unknown) ". Allowed: :limit")))
      (when-not (and (integer? limit) (pos? limit))
        (invalid :limit limit ":limit must be a positive integer"))
      (try
        (loop [offset 0
               rows []
               pages 0]
          (let [size (long (min max-page-size (- limit (count rows))))
                result (fetch-page ticker offset size)
                request {:ticker ticker :limit limit :pages (inc pages)}]
            (if-not (:ok? result)
              (assoc result :request request)
              (let [{page :rows total :total} (:data result)
                    rows (into rows page)]
                (if (or (< (count page) size)
                        (>= (count rows) limit)
                        (and (integer? total) (>= (count rows) total)))
                  {:ok? true
                   :data (vec (sort-by (juxt (comp nil? :announced-epoch) :announced-epoch) rows))
                   :request request}
                  (recur (+ offset size) rows (inc pages)))))))
        (catch Exception e
          {:ok? false :error {:type :exception :ticker ticker :message (.getMessage e)}}))))

(defn fetch-earnings-dates
  "Fetch earnings announcement events for a ticker (simple API). Options as
   for fetch-earnings-dates*. Returns the rows, or [] on failure."
  [ticker & {:keys [limit] :or {limit 100}}]
  (let [result (fetch-earnings-dates* ticker :limit limit)]
    (if (:ok? result) (:data result) [])))

(comment
  (fetch-earnings-history "AAPL")

  (fetch-earnings-dates "AAPL" :limit 300))
