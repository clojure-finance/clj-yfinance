(ns clj-yfinance.experimental.fundamentals
  "EXPERIMENTAL: Fetch data from Yahoo Finance quoteSummary endpoint.

   This namespace provides access to company data via Yahoo's authenticated
   quoteSummary API. The underlying fetch-quotesummary* function accepts any
   combination of quoteSummary modules, making it easy to add new data types.

   Available modules (pass to fetch-quotesummary*):
   - financialData            - Price targets, margins, revenue, cash flow
   - defaultKeyStatistics     - P/E, market cap, beta, shares outstanding
   - assetProfile             - Sector, industry, description, employees
   - earningsTrend            - Analyst EPS/revenue estimates by period
   - recommendationTrend      - Buy/hold/sell counts over time
   - earningsHistory          - Historical EPS vs estimates
   - incomeStatementHistory   - Annual/quarterly income statements
   - balanceSheetHistory      - Annual/quarterly balance sheets
   - cashflowStatementHistory - Annual/quarterly cash flow statements

   WARNING: This is experimental and may break at any time. Yahoo can change
   or block authentication without notice.

   For production use, consider stable alternatives:
   - AlphaVantage (free tier, good fundamentals)
   - Financial Modeling Prep (comprehensive)
   - Polygon.io (professional-grade)

   Implementation notes:
   - Returns ALL fields from Yahoo (no filtering)
   - Session auto-refreshes every hour
   - MAX 1 retry on authentication failure"
  (:require [clj-yfinance.experimental.auth :as auth]
            [cheshire.core :as json]))

(def ^:private base-url "https://query1.finance.yahoo.com/v10/finance/quoteSummary")
(def ^:private fundamentals-modules "financialData,defaultKeyStatistics")

(defn- err [type message & {:keys [status ticker url suggestion yahoo-error]}]
  {:type        type
   :message     message
   :ticker      ticker
   :url         url
   :status      status
   :yahoo-error yahoo-error
   :suggestion  (or suggestion
                    "If this persists, Yahoo may be blocking requests. Consider using AlphaVantage or Financial Modeling Prep.")})

(defn- build-quotesummary-url [ticker modules]
  (let [session (auth/get-session)
        crumb   (:crumb session)]
    (when-not crumb
      (throw (ex-info "No crumb available" {:type :no-crumb})))
    (str base-url "/"
         (java.net.URLEncoder/encode ticker "UTF-8")
         "?crumb=" (java.net.URLEncoder/encode crumb "UTF-8")
         "&modules=" modules)))

(defn- parse-quotesummary-response [response ticker]
  (let [status (.statusCode response)
        body   (.body response)]
    (cond
      (= 200 status)
      (try
        (let [data   (json/parse-string body true)
              result (-> data :quoteSummary :result first)
              error  (-> data :quoteSummary :error)]
          (cond
            error
            {:ok? false
             :error (err :api-error
                         (str "Yahoo API error: " (:description error))
                         :ticker ticker
                         :yahoo-error error
                         :suggestion "Check that the ticker symbol is valid.")}
            (nil? result)
            {:ok? false
             :error (err :missing-data
                         "No quoteSummary result in response"
                         :ticker ticker
                         :suggestion "Ticker may not have this data available. Try a different ticker.")}
            :else
            {:ok? true :data result}))
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
                   "Ticker not found or endpoint unavailable"
                   :status 404
                   :ticker ticker
                   :suggestion "Verify the ticker symbol is correct. Some tickers may not have this data available.")}

      (= 429 status)
      {:ok? false
       :error (err :rate-limited
                   "Rate limit exceeded"
                   :status 429
                   :ticker ticker
                   :suggestion "Yahoo is rate limiting requests. Wait several minutes before retrying.")}

      :else
      {:ok? false
       :error (err :http-error
                   (str "HTTP error " status)
                   :status status
                   :ticker ticker)})))

(defn fetch-quotesummary*
  "Shared foundation: fetch any quoteSummary modules for a ticker (verbose API).

   This is the base function used by all quoteSummary-based fetch functions.
   New data types (analyst, financials, company info) are built on top of this.

   Parameters:
   - ticker  - Ticker symbol string (e.g. \"AAPL\")
   - modules - Comma-separated Yahoo quoteSummary module names
               (e.g. \"financialData,defaultKeyStatistics\")

   Returns:
   {:ok? true :data {...}} - :data keys match the requested module names
   {:ok? false :error {...}} on failure

   Error types: :auth-failed, :session-failed, :api-error, :http-error,
                :rate-limited, :parse-error, :missing-data, :request-failed,
                :exception

   Example:
   (fetch-quotesummary* \"AAPL\" \"assetProfile,financialData\")
   => {:ok? true
       :data {:assetProfile {:sector \"Technology\"
                             :industry \"Consumer Electronics\" ...}
              :financialData {:currentPrice {:raw 255.78 :fmt \"255.78\"} ...}}}"
  [ticker modules]
  (try
    (let [url    (build-quotesummary-url ticker modules)
          result (auth/authenticated-request url)]
      (if (:ok? result)
        (parse-quotesummary-response (:response result) ticker)
        {:ok? false
         :error (assoc (:error result) :ticker ticker)}))
    (catch Exception e
      {:ok? false
       :error (err :exception
                   (str "Unexpected error: " (.getMessage e))
                   :ticker ticker)})))

(defn fetch-fundamentals*
  "Fetch fundamentals data for a ticker (verbose API).

   Returns {:ok? true :data {...}} or {:ok? false :error {...}}.

   :data contains fields from two modules:
   - :financialData        - currentPrice, targets, margins, revenue, ROE, recommendations
   - :defaultKeyStatistics - beta, P/E ratios, market cap, shares, 52-week change

   Numeric values are {:raw <number> :fmt <string>} maps.

   Example:
   (fetch-fundamentals* \"AAPL\")
   => {:ok? true
       :data {:financialData {:currentPrice {:raw 255.78 :fmt \"255.78\"}
                              :recommendationKey \"buy\" ...}
              :defaultKeyStatistics {:beta {:raw 1.107 :fmt \"1.11\"} ...}}}"
  [ticker]
  (fetch-quotesummary* ticker fundamentals-modules))

(defn fetch-fundamentals
  "Fetch fundamentals data for a ticker (simple API).

   Returns map of fundamentals data or nil on failure.
   For error details, use fetch-fundamentals* instead.

   Example:
   (fetch-fundamentals \"AAPL\")
   => {:financialData {:currentPrice {:raw 255.78 :fmt \"255.78\"} ...}
       :defaultKeyStatistics {:beta {:raw 1.107 :fmt \"1.11\"} ...}}"
  [ticker]
  (let [result (fetch-fundamentals* ticker)]
    (when (:ok? result)
      (:data result))))

(defn fetch-company-info*
  "Fetch company profile for a ticker (verbose API).

   Returns {:ok? true :data {...}} or {:ok? false :error {...}}.

   :data contains the :assetProfile map with fields including:
   - :sector, :sectorKey, :industry, :industryKey
   - :longBusinessSummary - Full company description
   - :fullTimeEmployees
   - :country, :city, :state, :zip, :address1
   - :phone, :website, :irWebsite
   - :companyOfficers - Vector of officer maps (name, title, age, totalPay)
   - :overallRisk, :auditRisk, :boardRisk, :compensationRisk,
     :shareHolderRightsRisk, :governanceEpochDate

   Example:
   (fetch-company-info* \"AAPL\")
   => {:ok? true
       :data {:assetProfile {:sector \"Technology\"
                             :industry \"Consumer Electronics\"
                             :fullTimeEmployees 150000
                             :longBusinessSummary \"Apple Inc. designs...\"
                             :companyOfficers [{:name \"Mr. Timothy D. Cook\"
                                               :title \"CEO & Director\"
                                               :totalPay {:raw 16759518 :fmt \"16.76M\"}} ...]}}}"
  [ticker]
  (fetch-quotesummary* ticker "assetProfile"))

(defn fetch-company-info
  "Fetch company profile for a ticker (simple API).

   Returns the :assetProfile map directly, or nil on failure.
   For error details, use fetch-company-info* instead.

   Example:
   (fetch-company-info \"AAPL\")
   => {:sector \"Technology\"
       :industry \"Consumer Electronics\"
       :fullTimeEmployees 150000
       :longBusinessSummary \"Apple Inc. designs...\"
       :companyOfficers [...]}"
  [ticker]
  (let [result (fetch-company-info* ticker)]
    (when (:ok? result)
      (-> result :data :assetProfile))))

(def ^:private analyst-modules "earningsTrend,recommendationTrend,earningsHistory")

(defn fetch-analyst*
  "Fetch analyst estimates and recommendations for a ticker (verbose API).

   Returns {:ok? true :data {...}} or {:ok? false :error {:}}

   :data contains three modules:

   :earningsTrend - EPS and revenue estimates by period
     :trend - vector of 4 periods (\"0q\" current quarter, \"1q\" next quarter,
              \"0y\" current year, \"1y\" next year), each with:
       - :earningsEstimate - avg/low/high EPS estimate, numberOfAnalysts, growth
       - :revenueEstimate  - avg/low/high revenue estimate, numberOfAnalysts, growth
       - :epsTrend         - current/7daysAgo/30daysAgo/60daysAgo/90daysAgo
       - :epsRevisions     - upLast7days/upLast30days/downLast30days/downLast7Days

   :recommendationTrend - Analyst buy/sell counts over 4 months
     :trend - vector of 4 months (\"0m\" current, \"-1m\", \"-2m\", \"-3m\"), each with:
       - :strongBuy, :buy, :hold, :sell, :strongSell counts

   :earningsHistory - Actual vs estimated EPS for last 4 quarters
     :history - vector of 4 quarters, each with:
       - :epsActual, :epsEstimate, :epsDifference, :surprisePercent
       - :quarter (epoch timestamp), :period (e.g. \"-4q\")

   Example:
   (fetch-analyst* \"AAPL\")
   => {:ok? true
       :data {:earningsTrend {:trend [{:period \"0q\"
                                       :earningsEstimate {:avg {:raw 1.95 :fmt \"1.95\"} ...}
                                       :revenueEstimate  {:avg {:raw 109078529710 ...} ...}
                                       :epsTrend {:current {:raw 1.95 :fmt \"1.95\"} ...}
                                       :epsRevisions {:upLast7days {:raw 1} ...}} ...]}
              :recommendationTrend {:trend [{:period \"0m\"
                                             :strongBuy 5 :buy 23 :hold 16
                                             :sell 1 :strongSell 1} ...]}
              :earningsHistory {:history [{:epsActual {:raw 1.65 :fmt \"1.65\"}
                                           :epsEstimate {:raw 1.62 :fmt \"1.62\"}
                                           :surprisePercent {:raw 0.0169 :fmt \"1.69%\"}} ...]}}}"
  [ticker]
  (fetch-quotesummary* ticker analyst-modules))

(defn fetch-analyst
  "Fetch analyst estimates and recommendations for a ticker (simple API).

   Returns the data map with :earningsTrend, :recommendationTrend, and
   :earningsHistory keys, or nil on failure.
   For error details, use fetch-analyst* instead.

   Example:
   (fetch-analyst \"AAPL\")
   => {:earningsTrend    {:trend [...]}
       :recommendationTrend {:trend [{:period \"0m\" :strongBuy 5 :buy 23 ...} ...]}
       :earningsHistory  {:history [...]}}"
  [ticker]
  (let [result (fetch-analyst* ticker)]
    (when (:ok? result)
      (:data result))))

(defn- financials-modules [period]
  (case period
    :annual    "incomeStatementHistory,balanceSheetHistory,cashflowStatementHistory"
    :quarterly "incomeStatementHistoryQuarterly,balanceSheetHistoryQuarterly,cashflowStatementHistoryQuarterly"
    (throw (ex-info "Invalid :period - must be :annual or :quarterly" {:period period}))))

(defn fetch-financials*
  "Fetch financial statements for a ticker (verbose API).

   Options:
   - :period - :annual (default) or :quarterly

   Returns {:ok? true :data {...}} or {:ok? false :error {...}}.

   NOTE: Yahoo partially restricts these modules. Fields reliably available:
   - Income statement: :totalRevenue, :netIncome (4 periods)
   - Balance sheet:    :endDate only (other fields blocked by Yahoo)
   - Cash flow:        :netIncome, :endDate only (other fields blocked by Yahoo)

   For comprehensive financial statements, consider Financial Modeling Prep
   or AlphaVantage as stable alternatives.

   Annual :data keys:
   - :incomeStatementHistory    - {:incomeStatementHistory [<4 annual periods>]}
   - :balanceSheetHistory       - {:balanceSheetStatements [<4 annual periods>]}
   - :cashflowStatementHistory  - {:cashflowStatements [<4 annual periods>]}

   Quarterly :data keys:
   - :incomeStatementHistoryQuarterly   - {:incomeStatementHistory [<4 quarters>]}
   - :balanceSheetHistoryQuarterly      - {:balanceSheetStatements [<4 quarters>]}
   - :cashflowStatementHistoryQuarterly - {:cashflowStatements [<4 quarters>]}

   Each period map includes :endDate {:raw <epoch> :fmt <date-string>} and
   numeric fields as {:raw <number> :fmt <string>} maps.

   Example:
   (fetch-financials* \"MSFT\")
   => {:ok? true
       :data {:incomeStatementHistory
              {:incomeStatementHistory
               [{:endDate {:raw 1751241600 :fmt \"2025-06-30\"}
                 :totalRevenue {:raw 281724000000 :fmt \"281.72B\"}
                 :netIncome {:raw 101832000000 :fmt \"101.83B\"} ...} ...]}}}

   (fetch-financials* \"MSFT\" :period :quarterly)
   => {:ok? true :data {:incomeStatementHistoryQuarterly {...} ...}}"
  [ticker & {:keys [period] :or {period :annual}}]
  (try
    (fetch-quotesummary* ticker (financials-modules period))
    (catch clojure.lang.ExceptionInfo e
      {:ok? false
       :error {:type    :invalid-opts
               :message (ex-message e)
               :ticker  ticker}})))

(defn fetch-financials
  "Fetch financial statements for a ticker (simple API).

   Returns the data map or nil on failure.
   Options: :period - :annual (default) or :quarterly
   For error details, use fetch-financials* instead.

   Example:
   (fetch-financials \"MSFT\")
   => {:incomeStatementHistory {...}
       :balanceSheetHistory {...}
       :cashflowStatementHistory {...}}

   (fetch-financials \"MSFT\" :period :quarterly)
   => {:incomeStatementHistoryQuarterly {...} ...}"
  [ticker & {:keys [period] :or {period :annual}}]
  (let [result (fetch-financials* ticker :period period)]
    (when (:ok? result)
      (:data result))))
