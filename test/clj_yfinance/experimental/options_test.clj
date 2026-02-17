(ns clj-yfinance.experimental.options-test
  "Tests for experimental options module.

   These tests do NOT make live network calls to Yahoo Finance.
   They test pure functions using mock HTTP responses and fixtures."
  (:require [clojure.test :refer :all]
            [clj-yfinance.experimental.options]))

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
  "{\"optionChain\":{\"result\":[{\"underlyingSymbol\":\"AAPL\",\"expirationDates\":[1771372800,1771977600],\"strikes\":[200.0,210.0,220.0],\"quote\":{\"regularMarketPrice\":255.78},\"options\":[{\"expirationDate\":1771372800,\"calls\":[{\"contractSymbol\":\"AAPL260218C00210000\",\"strike\":210.0,\"bid\":44.6,\"ask\":47.6,\"lastPrice\":46.1,\"impliedVolatility\":1.447,\"openInterest\":100,\"volume\":50,\"inTheMoney\":true,\"expiration\":1771372800,\"lastTradeDate\":1771200000,\"percentChange\":0.5,\"change\":0.25}],\"puts\":[{\"contractSymbol\":\"AAPL260218P00210000\",\"strike\":210.0,\"bid\":0.01,\"ask\":0.08,\"lastPrice\":0.04,\"impliedVolatility\":1.453,\"openInterest\":200,\"volume\":75,\"inTheMoney\":false,\"expiration\":1771372800,\"lastTradeDate\":1771100000,\"percentChange\":-1.2,\"change\":-0.01}]}]}],\"error\":null}}")

(def ^:private api-error-json
  "{\"optionChain\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No options data found for ticker\"}}}")

(def ^:private null-result-json
  "{\"optionChain\":{\"result\":null,\"error\":null}}")

(def ^:private empty-result-json
  "{\"optionChain\":{\"result\":[],\"error\":null}}")

;; ---------------------------------------------------------------------------
;; err helper tests
;; ---------------------------------------------------------------------------

(deftest err-test
  (testing "err constructs correct error map"
    (let [e (#'clj-yfinance.experimental.options/err
             :http-error "not found"
             :ticker "AAPL"
             :status 404
             :suggestion "check ticker")]
      (is (= :http-error (:type e)))
      (is (= "not found" (:message e)))
      (is (= "AAPL" (:ticker e)))
      (is (= 404 (:status e)))
      (is (= "check ticker" (:suggestion e)))))

  (testing "err uses default suggestion when none provided"
    (let [e (#'clj-yfinance.experimental.options/err :parse-error "bad json")]
      (is (string? (:suggestion e)))
      (is (pos? (count (:suggestion e))))))

  (testing "err with minimal args produces valid map"
    (let [e (#'clj-yfinance.experimental.options/err :exception "oops")]
      (is (= :exception (:type e)))
      (is (= "oops" (:message e)))
      (is (nil? (:ticker e)))
      (is (nil? (:status e))))))

;; ---------------------------------------------------------------------------
;; parse-options-response tests
;; ---------------------------------------------------------------------------

(deftest parse-response-success-test
  (testing "200 with valid result returns ok? true with expected keys"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 success-json) "AAPL")]
      (is (true? (:ok? result)))
      (let [data (:data result)]
        (is (= "AAPL" (:underlying-symbol data)))
        (is (= [1771372800 1771977600] (:expiration-dates data)))
        (is (= [200.0 210.0 220.0] (:strikes data)))
        (is (= 1771372800 (:expiration-date data)))
        (is (map? (:quote data)))
        (is (= 255.78 (-> data :quote :regularMarketPrice)))
        (is (vector? (:calls data)))
        (is (vector? (:puts data))))))

  (testing "200 - calls contain expected contract fields"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 success-json) "AAPL")
          call (-> result :data :calls first)]
      (is (= "AAPL260218C00210000" (:contractSymbol call)))
      (is (= 210.0 (:strike call)))
      (is (= 44.6 (:bid call)))
      (is (= 47.6 (:ask call)))
      (is (= 46.1 (:lastPrice call)))
      (is (= 1.447 (:impliedVolatility call)))
      (is (= 100 (:openInterest call)))
      (is (= 50 (:volume call)))
      (is (true? (:inTheMoney call)))
      (is (= 1771372800 (:expiration call)))
      (is (= 1771200000 (:lastTradeDate call)))))

  (testing "200 - puts contain expected contract fields"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 success-json) "AAPL")
          put (-> result :data :puts first)]
      (is (= "AAPL260218P00210000" (:contractSymbol put)))
      (is (= 210.0 (:strike put)))
      (is (false? (:inTheMoney put)))))

  (testing "200 with Yahoo API error returns ok? false with :api-error"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 api-error-json) "INVALID")]
      (is (false? (:ok? result)))
      (is (= :api-error (-> result :error :type)))
      (is (= "INVALID" (-> result :error :ticker)))
      (is (string? (-> result :error :message)))))

  (testing "200 with null result returns ok? false with :missing-data"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 null-result-json) "AAPL")]
      (is (false? (:ok? result)))
      (is (= :missing-data (-> result :error :type)))))

  (testing "200 with empty result array returns ok? false with :missing-data"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 empty-result-json) "AAPL")]
      (is (false? (:ok? result)))
      (is (= :missing-data (-> result :error :type)))))

  (testing "200 with invalid JSON returns ok? false with :parse-error"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 200 "not valid json") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :parse-error (-> result :error :type))))))

(deftest parse-response-http-errors-test
  (testing "401 returns :auth-failed with status 401"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 401 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :auth-failed (-> result :error :type)))
      (is (= 401 (-> result :error :status)))))

  (testing "404 returns :http-error with status 404"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 404 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :http-error (-> result :error :type)))
      (is (= 404 (-> result :error :status)))))

  (testing "429 returns :rate-limited with status 429"
    (let [result (#'clj-yfinance.experimental.options/parse-options-response
                  (mock-response 429 "") "AAPL")]
      (is (false? (:ok? result)))
      (is (= :rate-limited (-> result :error :type)))
      (is (= 429 (-> result :error :status)))))

  (testing "5xx returns :http-error"
    (doseq [status [500 502 503]]
      (let [result (#'clj-yfinance.experimental.options/parse-options-response
                    (mock-response status "") "AAPL")]
        (is (false? (:ok? result)))
        (is (= :http-error (-> result :error :type)))
        (is (= status (-> result :error :status)))))))

(deftest parse-response-ticker-propagation-test
  (testing "ticker is propagated into all error responses"
    (doseq [[status expected-type] [[401 :auth-failed]
                                    [404 :http-error]
                                    [429 :rate-limited]
                                    [500 :http-error]]]
      (let [result (#'clj-yfinance.experimental.options/parse-options-response
                    (mock-response status "") "TSLA")]
        (is (= "TSLA" (-> result :error :ticker))
            (str "ticker not propagated for status " status))))))

;; ---------------------------------------------------------------------------
;; Run tests helper
;; ---------------------------------------------------------------------------

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.options-test))

(comment
  (run-tests)
  (err-test)
  (parse-response-success-test)
  (parse-response-http-errors-test)
  (parse-response-ticker-propagation-test))
