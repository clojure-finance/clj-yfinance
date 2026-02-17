(ns clj-yfinance.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-yfinance.core :as yf]
            [clj-yfinance.parse :as parse]
            [clj-yfinance.validation :as validation]
            [clj-yfinance.http :as http])
  (:import [java.time Instant]))

;; Pure function tests - no network calls required

(deftest test-epoch-conversion
  (testing "->epoch converts Instant to epoch seconds"
    (let [instant (Instant/ofEpochSecond 1704067200)]
      (is (= 1704067200 (#'parse/->epoch instant)))))

  (testing "->epoch passes through long values"
    (is (= 1704067200 (#'parse/->epoch 1704067200))))

  (testing "->epoch returns nil for nil"
    (is (nil? (#'parse/->epoch nil))))

  (testing "->epoch throws on invalid input"
    (is (thrown? Exception (#'parse/->epoch "invalid")))))

(deftest test-url-encoding
  (testing "encode-path-segment encodes special characters"
    (is (= "%5EGSPC" (#'parse/encode-path-segment "^GSPC")))
    (is (= "BRK-B" (#'parse/encode-path-segment "BRK-B")))
    (is (= "AAPL" (#'parse/encode-path-segment "AAPL")))))

(deftest test-build-query
  (testing "build-query creates proper query strings"
    (is (= "interval=1d&range=1mo"
           (#'parse/build-query {:interval "1d" :range "1mo"}))))

  (testing "build-query removes nil values"
    (is (= "interval=1d"
           (#'parse/build-query {:interval "1d" :range nil}))))

  (testing "build-query encodes values"
    (is (= "events=div%7Csplit"
           (#'parse/build-query {:events "div|split"}))))

  (testing "build-query handles empty map"
    (is (= "" (#'parse/build-query {})))))

(deftest test-interval-range-warnings
  (testing "Valid interval/range combinations return no warnings"
    (is (empty? (#'validation/interval-range-warnings {:interval "1d" :period "1mo"})))
    (is (empty? (#'validation/interval-range-warnings {:interval "1h" :period "1mo"})))
    (is (empty? (#'validation/interval-range-warnings {:interval "1m" :period "1d"}))))

  (testing "Invalid interval/range combinations return warnings"
    (let [warnings (#'validation/interval-range-warnings {:interval "1m" :period "1mo"})]
      (is (= 1 (count warnings)))
      (is (= :interval-too-wide (:type (first warnings))))
      (is (= "1m" (:interval (first warnings))))
      (is (= 7 (:max-days (first warnings))))
      (is (= 30 (:requested-days (first warnings))))))

  (testing "Warnings with custom start/end dates"
    (let [start 1704067200
          end (+ start (* 10 86400))
          warnings (#'validation/interval-range-warnings {:interval "1m" :start start :end end})]
      (is (= 1 (count warnings)))
      (is (= 10 (:requested-days (first warnings)))))))

(deftest test-chart-time-params
  (testing "chart-time-params with period only"
    (is (= {:range "1mo"}
           (#'validation/chart-time-params {:period "1mo"}))))

  (testing "chart-time-params with start only"
    (let [result (#'validation/chart-time-params {:start 1704067200})]
      (is (= 1704067200 (:period1 result)))
      (is (number? (:period2 result)))
      (is (>= (:period2 result) 1704067200))))

  (testing "chart-time-params with start and end"
    (is (= {:period1 1704067200 :period2 1706659200}
           (#'validation/chart-time-params {:start 1704067200 :end 1706659200}))))

  (testing "chart-time-params with Instant objects"
    (let [start (Instant/ofEpochSecond 1704067200)
          end (Instant/ofEpochSecond 1706659200)
          result (#'validation/chart-time-params {:start start :end end})]
      (is (= 1704067200 (:period1 result)))
      (is (= 1706659200 (:period2 result))))))

(deftest test-parse-chart-body
  (testing "parse-chart-body with valid JSON"
    (let [json "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":100}}]}}"
          result (#'parse/parse-chart-body json)]
      (is (:ok? result))
      (is (map? (:data result)))
      (is (= 100 (-> result :data :meta :regularMarketPrice)))))

  (testing "parse-chart-body with API error"
    (let [json "{\"chart\":{\"error\":{\"description\":\"No data found\"}}}"
          result (#'parse/parse-chart-body json)]
      (is (not (:ok? result)))
      (is (= :api-error (-> result :error :type)))
      (is (= "No data found" (-> result :error :message)))))

  (testing "parse-chart-body with invalid JSON"
    (let [result (#'parse/parse-chart-body "not valid json {{")]
      (is (not (:ok? result)))
      (is (= :parse-error (-> result :error :type)))
      (is (string? (-> result :error :message)))))

  (testing "parse-chart-body with empty result returns :no-data error"
    (let [json "{\"chart\":{\"result\":[]}}"
          result (#'parse/parse-chart-body json)]
      (is (false? (:ok? result)))
      (is (= :no-data (-> result :error :type))))))

(deftest test-ok-err-constructors
  (testing "ok creates success result"
    (let [result (#'parse/ok {:price 100})]
      (is (:ok? result))
      (is (= {:price 100} (:data result)))))

  (testing "err creates error result"
    (let [result (#'parse/err {:type :test-error :message "test"})]
      (is (not (:ok? result)))
      (is (= :test-error (-> result :error :type)))
      (is (= "test" (-> result :error :message))))))

(deftest test-validate-opts
  (testing "Valid options pass validation"
    (is (:ok? (#'validation/validate-opts {:period "1mo" :interval "1d"})))
    (is (:ok? (#'validation/validate-opts {:period "1y"})))
    (is (:ok? (#'validation/validate-opts {:start 1704067200})))
    (is (:ok? (#'validation/validate-opts {:start 1704067200 :interval "5m"}))))

  (testing "Missing period and start is rejected"
    (let [result (#'validation/validate-opts {:interval "5m"})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :period (-> result :error :field))))
    (let [result (#'validation/validate-opts {})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :period (-> result :error :field)))))

  (testing "End without start is rejected"
    (let [result (#'validation/validate-opts {:end 1706659200})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :end (-> result :error :field)))))

  (testing "Invalid start type is rejected"
    (let [result (#'validation/validate-opts {:start "not-a-number"})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :start (-> result :error :field)))))

  (testing "Invalid end type is rejected"
    (let [result (#'validation/validate-opts {:start 1704067200 :end "not-a-number"})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :end (-> result :error :field)))))

  (testing "Invalid period is rejected"
    (let [result (#'validation/validate-opts {:period "invalid"})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :period (-> result :error :field)))
      (is (= "invalid" (-> result :error :value)))
      (is (set? (-> result :error :allowed)))
      (is (string? (-> result :error :message)))))

  (testing "Invalid interval is rejected"
    (let [result (#'validation/validate-opts {:period "1mo" :interval "2h"})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :interval (-> result :error :field)))
      (is (= "2h" (-> result :error :value)))))

  (testing "start > end is rejected"
    (let [result (#'validation/validate-opts {:start 1706659200 :end 1704067200})]
      (is (not (:ok? result)))
      (is (= :invalid-opts (-> result :error :type)))
      (is (= :start/end (-> result :error :field)))
      (is (= "start must be <= end" (-> result :error :message)))))

  (testing "Valid start/end passes"
    (is (:ok? (#'validation/validate-opts {:start 1704067200 :end 1706659200}))))

  (testing "start == end passes"
    (is (:ok? (#'validation/validate-opts {:start 1704067200 :end 1704067200})))))

(deftest test-camel-kebab-conversion
  (testing "camel->kebab converts standard camelCase"
    (is (= :regular-market-price (#'parse/camel->kebab :regularMarketPrice)))
    (is (= :fifty-two-week-high (#'parse/camel->kebab :fiftyTwoWeekHigh)))
    (is (= :exchange-name (#'parse/camel->kebab :exchangeName))))

  (testing "camel->kebab handles special cases"
    (is (= :gmt-offset (#'parse/camel->kebab :gmtoffset))))

  (testing "camel->kebab handles already kebab-case"
    (is (= :already-kebab (#'parse/camel->kebab :already-kebab))))

  (testing "camel->kebab handles single word"
    (is (= :symbol (#'parse/camel->kebab :symbol))))

  (testing "kebabize-keys converts all keys in map"
    (let [input {:regularMarketPrice 100
                 :fiftyTwoWeekHigh 200
                 :gmtoffset 3600
                 :symbol "AAPL"}
          result (#'parse/kebabize-keys input)]
      (is (= :regular-market-price (first (keys (select-keys result [:regular-market-price])))))
      (is (= :fifty-two-week-high (first (keys (select-keys result [:fifty-two-week-high])))))
      (is (= :gmt-offset (first (keys (select-keys result [:gmt-offset])))))
      (is (= :symbol (first (keys (select-keys result [:symbol])))))
      (is (= 100 (:regular-market-price result)))
      (is (= 200 (:fifty-two-week-high result)))
      (is (= 3600 (:gmt-offset result)))
      (is (= "AAPL" (:symbol result))))))

(deftest test-retryable
  (testing "Rate limited errors are retryable"
    (is (#'http/retryable? {:ok? false :error {:type :rate-limited}})))

  (testing "Connection errors are retryable"
    (is (#'http/retryable? {:ok? false :error {:type :connection-error}})))

  (testing "5xx HTTP errors are retryable"
    (is (#'http/retryable? {:ok? false :error {:type :http-error :status 500}}))
    (is (#'http/retryable? {:ok? false :error {:type :http-error :status 503}}))
    (is (#'http/retryable? {:ok? false :error {:type :http-error :status 599}})))

  (testing "4xx HTTP errors are not retryable"
    (is (not (#'http/retryable? {:ok? false :error {:type :http-error :status 400}})))
    (is (not (#'http/retryable? {:ok? false :error {:type :http-error :status 404}})))
    (is (not (#'http/retryable? {:ok? false :error {:type :http-error :status 429}}))))

  (testing "API errors are not retryable"
    (is (not (#'http/retryable? {:ok? false :error {:type :api-error}}))))

  (testing "Parse errors are not retryable"
    (is (not (#'http/retryable? {:ok? false :error {:type :parse-error}}))))

  (testing "Invalid opts errors are not retryable"
    (is (not (#'http/retryable? {:ok? false :error {:type :invalid-opts}}))))

  (testing "Success results are not retryable"
    (is (not (#'http/retryable? {:ok? true :data {:price 100}})))))

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.core-test))
