(ns clj-yfinance.experimental.fundamentals
  "⚠️ EXPERIMENTAL: Fetch fundamentals data from Yahoo Finance.
   
   This namespace provides access to company fundamentals including:
   - Financial metrics (P/E ratio, market cap, EBITDA, profit margins)
   - Analyst data (price targets, recommendations, number of analysts)
   - Key statistics (enterprise value, shares outstanding, beta)
   
   WARNING: This is experimental and may break at any time.
   
   Why experimental?
   - Uses Yahoo's authenticated endpoints (requires cookie/crumb)
   - Yahoo can block or rate-limit requests at any time
   - More fragile than the stable chart API for prices/history
   - May require maintenance when Yahoo changes authentication
   
   For production use, consider stable alternatives:
   - AlphaVantage: https://www.alphavantage.co/ (free tier, good fundamentals)
   - Financial Modeling Prep: https://financialmodelingprep.com/ (comprehensive)
   - Polygon.io: https://polygon.io/ (professional-grade)
   
   Implementation notes:
   - Returns ALL fields from Yahoo (100+ fields)
   - Uses minimal modules: financialData, defaultKeyStatistics
   - Session auto-refreshes every hour
   - MAX 1 retry on authentication failure"
  (:require [clj-yfinance.experimental.auth :as auth]
            [cheshire.core :as json]))

;; Constants
(def ^:private base-url "https://query1.finance.yahoo.com/v10/finance/quoteSummary")
(def ^:private default-modules "financialData,defaultKeyStatistics")

;; Error construction
(defn- err
  "Construct error map with verbose details and suggestions."
  [type message & {:keys [status ticker url suggestion yahoo-error]}]
  {:type type
   :message message
   :ticker ticker
   :url url
   :status status
   :yahoo-error yahoo-error
   :suggestion (or suggestion
                   "If this persists, Yahoo may be blocking requests. Consider using AlphaVantage or Financial Modeling Prep.")})

;; URL construction
(defn- build-fundamentals-url [ticker]
  (let [session (auth/get-session) ; Ensure session is initialized
        crumb (:crumb session)]
    (when-not crumb
      (throw (ex-info "No crumb available" {:type :no-crumb})))
    (str base-url "/"
         (java.net.URLEncoder/encode ticker "UTF-8")
         "?crumb=" (java.net.URLEncoder/encode crumb "UTF-8")
         "&modules=" default-modules)))

;; Response parsing
(defn- parse-fundamentals-response [response ticker]
  (let [status (.statusCode response)
        body (.body response)]
    (cond
      ;; Success - parse JSON
      (= 200 status)
      (try
        (let [data (json/parse-string body true)
              result (-> data :quoteSummary :result first)
              error (-> data :quoteSummary :error)]
          (cond
            ;; Yahoo API error (e.g., invalid ticker)
            error
            {:ok? false
             :error (err :api-error
                         (str "Yahoo API error: " (:description error))
                         :ticker ticker
                         :yahoo-error error
                         :suggestion "Check that the ticker symbol is valid.")}

            ;; No result data
            (nil? result)
            {:ok? false
             :error (err :missing-data
                         "No quoteSummary result in response"
                         :ticker ticker
                         :suggestion "Ticker may not have fundamentals data available. Try a different ticker.")}

            ;; Success with data
            :else
            {:ok? true :data result}))
        (catch Exception e
          {:ok? false
           :error (err :parse-error
                       (str "Failed to parse JSON response: " (.getMessage e))
                       :ticker ticker)}))

      ;; 401 - Authentication failed (should have been retried already)
      (= 401 status)
      {:ok? false
       :error (err :auth-failed
                   "Authentication failed after retry"
                   :status 401
                   :ticker ticker
                   :suggestion "Yahoo may be blocking requests. Wait a few minutes and try again, or use AlphaVantage.")}

      ;; 404 - Invalid ticker or endpoint
      (= 404 status)
      {:ok? false
       :error (err :http-error
                   "Ticker not found or endpoint unavailable"
                   :status 404
                   :ticker ticker
                   :suggestion "Verify the ticker symbol is correct. Some tickers may not have fundamentals available.")}

      ;; 429 - Rate limited
      (= 429 status)
      {:ok? false
       :error (err :rate-limited
                   "Rate limit exceeded"
                   :status 429
                   :ticker ticker
                   :suggestion "Yahoo is rate limiting requests. Wait several minutes before retrying, or use a paid API service.")}

      ;; Other HTTP errors
      :else
      {:ok? false
       :error (err :http-error
                   (str "HTTP error " status)
                   :status status
                   :ticker ticker)})))

;; Public API - Verbose
(defn fetch-fundamentals*
  "Fetch fundamentals data for a ticker (verbose API).
   
   Returns:
   {:ok? true :data {...}} on success
   {:ok? false :error {...}} on failure
   
   The :data map contains ALL fields returned by Yahoo, including:
   
   Financial metrics:
   - :currentPrice, :targetMeanPrice, :targetHighPrice, :targetLowPrice
   - :marketCap, :enterpriseValue, :totalRevenue, :ebitda
   - :profitMargins, :operatingMargins, :returnOnEquity, :returnOnAssets
   - :revenuePerShare, :totalCashPerShare, :debtToEquity
   
   Analyst data:
   - :recommendationKey (e.g., 'buy', 'hold', 'sell')
   - :recommendationMean, :numberOfAnalystOpinions
   
   Key statistics:
   - :beta, :forwardPE, :trailingPE, :priceToBook
   - :sharesOutstanding, :floatShares, :heldPercentInstitutions
   - :52WeekChange, :SandP52WeekChange
   - :earningsQuarterlyGrowth
   
   And 80+ other fields. See Yahoo Finance website for field definitions.
   
   Error types:
   - :auth-failed - Cookie/crumb authentication failed
   - :session-failed - Session initialization failed  
   - :api-error - Yahoo API returned error (e.g., invalid ticker)
   - :http-error - Non-200 HTTP status
   - :rate-limited - Too many requests (HTTP 429)
   - :parse-error - JSON parsing failed
   - :missing-data - No quoteSummary result
   - :request-failed - Network/connection error
   
   Example:
   (fetch-fundamentals* \"AAPL\")
   => {:ok? true
       :data {:marketCap {:raw 4034211348480 :fmt \"4.03T\"}
              :currentPrice {:raw 255.78 :fmt \"255.78\"}
              :recommendationKey \"buy\"
              ...}}"
  [ticker]
  (try
    (let [url (build-fundamentals-url ticker)
          result (auth/authenticated-request url)]
      (if (:ok? result)
        (parse-fundamentals-response (:response result) ticker)
        {:ok? false
         :error (assoc (:error result) :ticker ticker)}))
    (catch Exception e
      {:ok? false
       :error (err :exception
                   (str "Unexpected error: " (.getMessage e))
                   :ticker ticker)})))

;; Public API - Simple
(defn fetch-fundamentals
  "Fetch fundamentals data for a ticker (simple API).
   
   Returns map of fundamentals data or nil on failure.
   
   This is a convenience wrapper around fetch-fundamentals* that:
   - Returns just the data on success
   - Returns nil on any error
   - Silently handles all errors
   
   For error details and debugging, use fetch-fundamentals* instead.
   
   Example:
   (fetch-fundamentals \"AAPL\")
   => {:marketCap {:raw 4034211348480 :fmt \"4.03T\"}
       :currentPrice {:raw 255.78 :fmt \"255.78\"}
       :recommendationKey \"buy\"
       ...}
   
   Or nil if the request failed."
  [ticker]
  (let [result (fetch-fundamentals* ticker)]
    (when (:ok? result)
      (:data result))))
