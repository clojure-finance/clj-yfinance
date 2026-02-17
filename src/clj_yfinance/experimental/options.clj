(ns clj-yfinance.experimental.options
  "EXPERIMENTAL: Fetch options chain data from Yahoo Finance.

   This namespace provides access to options chains via Yahoo's authenticated
   v7 options endpoint. Authentication is handled automatically via the shared
   session in clj-yfinance.experimental.auth.

   WARNING: This is experimental and may break at any time. Yahoo can change
   or block authentication without notice.

   For production use, consider stable alternatives:
   - AlphaVantage (free tier, options data available)
   - Polygon.io (professional-grade, comprehensive options)
   - Tradier (options-focused API)

   Implementation notes:
   - Uses /v7/finance/options endpoint (different from quoteSummary)
   - Without :expiration returns nearest expiry + full list of available dates
   - With :expiration (epoch seconds) returns full calls/puts chain for that date
   - Each contract map includes: strike, bid, ask, lastPrice, impliedVolatility,
     openInterest, volume, inTheMoney, contractSymbol, expiration, lastTradeDate
   - Session auto-refreshes every hour
   - MAX 1 retry on authentication failure"
  (:require [clj-yfinance.experimental.auth :as auth]
            [cheshire.core :as json]))

(def ^:private base-url "https://query1.finance.yahoo.com/v7/finance/options")

(defn- err [type message & {:keys [status ticker suggestion]}]
  {:type type
   :message message
   :ticker ticker
   :status status
   :suggestion (or suggestion
                   "If this persists, Yahoo may be blocking requests. Consider using Polygon.io or AlphaVantage.")})

(defn- build-options-url [ticker expiration]
  (let [session (auth/get-session)
        crumb (:crumb session)]
    (when-not crumb
      (throw (ex-info "No crumb available" {:type :no-crumb})))
    (cond-> (str base-url "/"
                 (java.net.URLEncoder/encode ticker "UTF-8")
                 "?crumb=" (java.net.URLEncoder/encode crumb "UTF-8"))
      expiration (str "&date=" expiration))))

(defn- parse-options-response [response ticker]
  (let [status (.statusCode response)
        body (.body response)]
    (cond
      (= 200 status)
      (try
        (let [data (json/parse-string body true)
              result (-> data :optionChain :result first)
              error (-> data :optionChain :error)]
          (cond
            error
            {:ok? false
             :error (err :api-error
                         (str "Yahoo API error: " (:description error))
                         :ticker ticker
                         :suggestion "Check that the ticker symbol is valid.")}
            (nil? result)
            {:ok? false
             :error (err :missing-data
                         "No options data in response"
                         :ticker ticker
                         :suggestion "Ticker may not have options available. Options require exchange-listed equities or ETFs.")}
            :else
            {:ok? true
             :data {:underlying-symbol (:underlyingSymbol result)
                    :expiration-dates (:expirationDates result)
                    :strikes (:strikes result)
                    :quote (:quote result)
                    :calls (-> result :options first :calls)
                    :puts (-> result :options first :puts)
                    :expiration-date (-> result :options first :expirationDate)}}))
        (catch Exception e
          {:ok? false
           :error (err :parse-error
                       (str "Failed to parse JSON response: " (.getMessage e))
                       :ticker ticker)}))

      (= 401 status)
      {:ok? false
       :error (err :auth-failed
                   "Authentication failed after retry"
                   :status 401
                   :ticker ticker
                   :suggestion "Yahoo may be blocking requests. Wait a few minutes and try again.")}

      (= 404 status)
      {:ok? false
       :error (err :http-error
                   "Ticker not found or options not available"
                   :status 404
                   :ticker ticker
                   :suggestion "Verify the ticker is correct and has listed options.")}

      (= 429 status)
      {:ok? false
       :error (err :rate-limited
                   "Rate limit exceeded"
                   :status 429
                   :ticker ticker
                   :suggestion "Wait several minutes before retrying.")}

      :else
      {:ok? false
       :error (err :http-error
                   (str "HTTP error " status)
                   :status status
                   :ticker ticker)})))

(defn fetch-options*
  "Fetch options chain for a ticker (verbose API).

   Parameters:
   - ticker      - Ticker symbol string (e.g. \"AAPL\")
   - :expiration - Epoch seconds (integer) for a specific expiry date.
                   Omit to get the nearest expiry + list of all available dates.

   Returns {:ok? true :data {...}} or {:ok? false :error {:}}.

   :data map keys:
   - :underlying-symbol - Ticker symbol
   - :expiration-dates  - Vector of all available expiry dates (epoch seconds)
   - :strikes           - Vector of all available strike prices
   - :expiration-date   - Epoch seconds of the returned chain's expiry
   - :calls             - Vector of call contract maps
   - :puts              - Vector of put contract maps
   - :quote             - Current quote data for the underlying

   Each contract map includes:
   - :contractSymbol   - e.g. \"AAPL260218C00210000\"
   - :strike           - Strike price (number)
   - :bid, :ask        - Current bid/ask (number)
   - :lastPrice        - Last traded price (number)
   - :impliedVolatility - IV (number, e.g. 0.35 = 35%)
   - :openInterest     - Open interest (integer)
   - :volume           - Volume (integer)
   - :inTheMoney       - Boolean
   - :expiration       - Contract expiry (epoch seconds)
   - :lastTradeDate    - Last trade timestamp (epoch seconds)
   - :percentChange, :change - Price change fields

   Error types: :auth-failed, :session-failed, :api-error, :http-error,
                :rate-limited, :parse-error, :missing-data, :request-failed,
                :exception

   Example - nearest expiry:
   (fetch-options* \"AAPL\")
   => {:ok? true
       :data {:underlying-symbol \"AAPL\"
              :expiration-dates [1771372800 1771545600 ...]   ; 23 dates
              :strikes [195.0 200.0 ... 335.0]               ; 41 strikes
              :expiration-date 1771372800
              :calls [{:contractSymbol \"AAPL260218C00210000\"
                       :strike 210.0 :bid 44.6 :ask 47.6
                       :impliedVolatility 1.447 :inTheMoney true ...} ...]
              :puts  [{:contractSymbol \"AAPL260218P00195000\"
                       :strike 195.0 :bid 0.01 :ask 0.08
                       :impliedVolatility 1.453 :inTheMoney false ...} ...]}}

   Example - specific expiry:
   (fetch-options* \"AAPL\" :expiration 1771372800)
   => {:ok? true
       :data {:calls [<31 contracts>] :puts [<32 contracts>] ...}}"
  [ticker & {:keys [expiration]}]
  (try
    (let [do-request (fn []
                       (let [url (build-options-url ticker expiration)
                             result (auth/authenticated-request url :retry-on-401? false)]
                         (if (:ok? result)
                           (parse-options-response (:response result) ticker)
                           {:ok? false :error (assoc (:error result) :ticker ticker)})))
          first-result (do-request)]
      (if (= :auth-failed (-> first-result :error :type))
        (do (auth/force-refresh!)
            (do-request))
        first-result))
    (catch Exception e
      {:ok? false
       :error (err :exception
                   (str "Unexpected error: " (.getMessage e))
                   :ticker ticker)})))

(defn fetch-options
  "Fetch options chain for a ticker (simple API).

   Returns the options data map or nil on failure.
   Options: :expiration - epoch seconds for a specific expiry date.
   For error details, use fetch-options* instead.

   Example:
   (fetch-options \"AAPL\")
   => {:underlying-symbol \"AAPL\"
       :expiration-dates [1771372800 ...]
       :strikes [195.0 200.0 ...]
       :expiration-date 1771372800
       :calls [{:strike 210.0 :bid 44.6 :ask 47.6 ...} ...]
       :puts  [{:strike 195.0 :bid 0.01 :ask 0.08 ...} ...]}

   (fetch-options \"AAPL\" :expiration 1771372800)
   => {:calls [<31 contracts>] :puts [<32 contracts>] ...}"
  [ticker & {:keys [expiration]}]
  (let [result (fetch-options* ticker :expiration expiration)]
    (when (:ok? result)
      (:data result))))
