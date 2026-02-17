(ns clj-yfinance.experimental.fundamentals-test
  "Tests for experimental fundamentals module.

   These tests do NOT make live network calls to Yahoo Finance.
   They test pure functions using mock HTTP responses and fixtures."
  (:require [clojure.test :refer :all]
            [clj-yfinance.experimental.fundamentals]))

;; ---------------------------------------------------------------------------
;; Mock HTTP response helper
;; ---------------------------------------------------------------------------

(defn- mock-response [status body]
  (reify java.net.http.HttpResponse
    (statusCode [_] status)
    (body [_] body)))

;; ---------------------------------------------------------------------------
;; JSON fixtures
;; ---------------------------------------------------------------------------

(def ^:private success-json
  "{\"quoteSummary\":{\"result\":[{\"financialData\":{\"currentPrice\":{\"raw\":255.78,\"fmt\":\"255.78\"},\"recommendationKey\":\"buy\"},\"defaultKeyStatistics\":{\"beta\":{\"raw\":1.107,\"fmt\":\"1.11\"}}}],\"error\":null}}")

(def ^:private api-error-json
  "{\"quoteSummary\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No fundamentals data found\"}}}")

(def ^:private null-result-json
  "{\"quoteSummary\":{\"result\":null,\"error\":null}}")

(def ^:private empty-result-json
  "{\"quoteSummary\":{\"result\":[],\"error\":null}}")

;; ---------------------------------------------------------------------------
;; err helper tests
;; ---------------------------------------------------------------------------

(deftest err-test
  (testing "err constructs correct error map"
    (let [e (#'clj-yfinance.experimental.fundamentals/err
             :http-error "not found"
             :ticker "AAPL"
             :status 404
             :suggestion "check ticker")]
      (is (= :http-error (:type e)))
      (is (= "not found" (:message e)))
      (is (= "AAPL" (:ticker e)))
      (is (= 404 (:status e)))
      (is (= "check ticker" (:suggestion e)))
      (is (nil? (:url e)))
      (is (nil? (:yahoo-error e)))))

  (testing "err uses default suggestion when none provided"
    (let [e (#'clj-yfinance.experimental.fundamentals/err :parse-error "bad json")]
      (is (string? (:suggestion e)))
      (is (pos? (count (:suggestion e))))))

  (testing "err includes yahoo-error when provided"
    (let [yahoo-err {:code "Not Found" :description "No data"}
          e (#'clj-yfinance.experimental.fundamentals/err
             :api-error "yahoo error"
             :yahoo-error yahoo-err)]
      (is (= yahoo-err (:yahoo-error e))))))

;; ---------------------------------------------------------------------------
;; parse-quotesummary-response tests
;; ---------------------------------------------------------------------------

(deftest parse-response-success-test
  (testing "200 with valid result returns ok? true"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 200 success-json) "AAPL")]
      (is (true? (:ok? result)))
      (is (map? (:data result)))
      (is (contains? (:data result) :financialData))
      (is (contains? (:data result) :defaultKeyStatistics))
      (is (= "buy" (-> result :data :financialData :recommendationKey)))
      (is (= 255.78 (-> result :data :financialData :currentPrice :raw)))))

  (testing "200 with Yahoo API error returns ok? false with :api-error"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 200 api-error-json) "INVALID")]
      (is (false? (:ok? result)))
      (is (= :api-error (-> result :error :type)))
      (is (= "INVALID" (-> result :error :ticker)))
      (is (some? (-> result :error :yahoo-error)))))

  (testing "200 with null result returns ok? false with :missing-data"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 200 null-result-json) "AAPL")]
      (is (false? (:ok? result)))
      (is (= :missing-data (-> result :error :type)))))

  (testing "200 with empty result array returns ok? false with :missing-data"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 200 empty-result-json) "AAPL")]
      (is (false? (:ok? result)))
      (is (= :missing-data (-> result :error :type)))))

  (testing "200 with invalid JSON returns ok? false with :parse-error"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 200 "not valid json") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :parse-error (-> result :error :type))))))

(deftest parse-response-http-errors-test
  (testing "401 returns :auth-failed"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 401 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :auth-failed (-> result :error :type)))
      (is (= 401 (-> result :error :status)))))

  (testing "404 returns :http-error with status 404"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 404 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :http-error (-> result :error :type)))
      (is (= 404 (-> result :error :status)))))

  (testing "429 returns :rate-limited"
    (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                  (mock-response 429 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :rate-limited (-> result :error :type)))
      (is (= 429 (-> result :error :status)))))

  (testing "5xx returns :http-error"
    (doseq [status [500 502 503]]
      (let [result (#'clj-yfinance.experimental.fundamentals/parse-quotesummary-response
                    (mock-response status "") "AAPL")]
        (is (false? (:ok? result)))
        (is (= :http-error (-> result :error :type)))
        (is (= status (-> result :error :status)))))))

;; ---------------------------------------------------------------------------
;; financials-modules tests
;; ---------------------------------------------------------------------------

(deftest financials-modules-test
  (testing ":annual returns annual module string"
    (let [m (#'clj-yfinance.experimental.fundamentals/financials-modules :annual)]
      (is (string? m))
      (is (clojure.string/includes? m "incomeStatementHistory"))
      (is (clojure.string/includes? m "balanceSheetHistory"))
      (is (clojure.string/includes? m "cashflowStatementHistory"))
      (is (not (clojure.string/includes? m "Quarterly")))))

  (testing ":quarterly returns quarterly module string"
    (let [m (#'clj-yfinance.experimental.fundamentals/financials-modules :quarterly)]
      (is (string? m))
      (is (clojure.string/includes? m "Quarterly"))))

  (testing "invalid period throws ExceptionInfo"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'clj-yfinance.experimental.fundamentals/financials-modules :bogus)))))

;; ---------------------------------------------------------------------------
;; fetch-financials* invalid period test (no network call)
;; ---------------------------------------------------------------------------

(deftest fetch-financials-invalid-period-test
  (testing "fetch-financials* with invalid :period returns :invalid-opts error"
    (let [result (clj-yfinance.experimental.fundamentals/fetch-financials* "AAPL" :period :bogus)]
      (is (false? (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= "AAPL" (-> result :error :ticker)))))

  (testing "fetch-financials with invalid :period returns nil"
    (is (nil? (clj-yfinance.experimental.fundamentals/fetch-financials "AAPL" :period :bogus)))))

;; ---------------------------------------------------------------------------
;; Module constants tests
;; ---------------------------------------------------------------------------

(deftest module-constants-test
  (testing "fundamentals-modules contains expected modules"
    (let [m @#'clj-yfinance.experimental.fundamentals/fundamentals-modules]
      (is (clojure.string/includes? m "financialData"))
      (is (clojure.string/includes? m "defaultKeyStatistics"))))

  (testing "analyst-modules contains expected modules"
    (let [m @#'clj-yfinance.experimental.fundamentals/analyst-modules]
      (is (clojure.string/includes? m "earningsTrend"))
      (is (clojure.string/includes? m "recommendationTrend"))
      (is (clojure.string/includes? m "earningsHistory")))))

;; ---------------------------------------------------------------------------
;; Run tests helper
;; ---------------------------------------------------------------------------

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.fundamentals-test))

(comment
  (run-tests)
  (err-test)
  (parse-response-success-test)
  (parse-response-http-errors-test)
  (financials-modules-test)
  (fetch-financials-invalid-period-test)
  (module-constants-test))
