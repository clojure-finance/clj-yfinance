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

(deftest test-cumulative-split-factor
  (let [splits {:1778160600 {:date 1778160600 :numerator 5.0 :denominator 1.0 :splitRatio "5:1"}
                :1500000000 {:date 1500000000 :numerator 2.0 :denominator 1.0 :splitRatio "2:1"}}]
    (testing "Position entered before all splits accumulates every factor"
      (is (= 10.0 (yf/cumulative-split-factor splits 1400000000))))

    (testing "Position entered between splits only picks up later splits"
      (is (= 5.0 (yf/cumulative-split-factor splits 1600000000))))

    (testing "Split at exactly `since` is excluded (same-day trades are post-split)"
      (is (= 1.0 (yf/cumulative-split-factor splits 1778160600))))

    (testing "Position entered after all splits is unaffected"
      (is (= 1.0 (yf/cumulative-split-factor splits 1800000000))))

    (testing "`until` bounds the window inclusively"
      (is (= 2.0 (yf/cumulative-split-factor splits 1400000000 1500000000)))
      (is (= 1.0 (yf/cumulative-split-factor splits 1400000000 1499999999))))

    (testing "Accepts java.time.Instant"
      (is (= 10.0 (yf/cumulative-split-factor splits (Instant/ofEpochSecond 1400000000)))))

    (testing "Empty splits map yields identity factor"
      (is (= 1.0 (yf/cumulative-split-factor {} 1400000000))))

    (testing "Reverse splits shrink the factor"
      (is (= 0.1 (yf/cumulative-split-factor
                  {:1600000000 {:date 1600000000 :numerator 1.0 :denominator 10.0 :splitRatio "1:10"}}
                  1400000000))))))

(deftest test-chart->rows
  (let [chart {:timestamp [1 2 3]
               :indicators {:quote [{:open [10.0 20.0 30.0] :high [11.0 22.0 33.0]
                                     :low [9.0 18.0 27.0] :close [10.0 20.0 30.0]
                                     :volume [100 200 300]}]
                            :adjclose [{:adjclose [5.0 nil 30.0]}]}}]
    (testing "auto-adjust scales OHLC by adjclose/close and keeps :adj-close"
      (let [[r1 r2 r3] (parse/chart->rows chart {:auto-adjust true})]
        (is (= {:timestamp 1 :open 5.0 :high 5.5 :low 4.5 :close 5.0 :volume 100 :adj-close 5.0} r1))
        (is (= {:timestamp 2 :open 20.0 :high 22.0 :low 18.0 :close 20.0 :volume 200} r2)
            "row without adjclose is left unadjusted and has no :adj-close")
        (is (= {:timestamp 3 :open 30.0 :high 33.0 :low 27.0 :close 30.0 :volume 300 :adj-close 30.0} r3))))

    (testing "auto-adjust false leaves OHLC raw and still includes :adj-close where present"
      (is (= [{:timestamp 1 :open 10.0 :high 11.0 :low 9.0 :close 10.0 :volume 100 :adj-close 5.0}
              {:timestamp 2 :open 20.0 :high 22.0 :low 18.0 :close 20.0 :volume 200}
              {:timestamp 3 :open 30.0 :high 33.0 :low 27.0 :close 30.0 :volume 300 :adj-close 30.0}]
             (parse/chart->rows chart {:auto-adjust false}))))

    (testing "missing adjclose series yields raw rows without :adj-close"
      (is (= [{:timestamp 1 :open 10.0 :high 11.0 :low 9.0 :close 10.0 :volume 100}]
             (parse/chart->rows (-> chart
                                    (assoc :timestamp [1])
                                    (update-in [:indicators :quote 0] #(into {} (map (fn [[k v]] [k [(first v)]]) %)))
                                    (update :indicators dissoc :adjclose))
                                {:auto-adjust true}))))

    (testing "non-positive close is not used as a divisor"
      (is (= [{:timestamp 1 :open 1.0 :high 1.0 :low 1.0 :close 0.0 :volume 1 :adj-close 2.0}]
             (parse/chart->rows {:timestamp [1]
                                 :indicators {:quote [{:open [1.0] :high [1.0] :low [1.0] :close [0.0] :volume [1]}]
                                              :adjclose [{:adjclose [2.0]}]}}
                                {:auto-adjust true}))))

    (testing "nil OHLC values pass through"
      (is (= [{:timestamp 1 :open nil :high nil :low nil :close 5.0 :volume nil :adj-close 5.0}]
             (parse/chart->rows {:timestamp [1]
                                 :indicators {:quote [{:open [nil] :high [nil] :low [nil] :close [10.0] :volume [nil]}]
                                              :adjclose [{:adjclose [5.0]}]}}
                                {:auto-adjust true}))))))

;; Trimmed from a real AAPL response (range=1mo&interval=1d&events=div|split)
(def ^:private chart-with-events-json
  "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"symbol\":\"AAPL\",\"exchangeName\":\"NMS\",\"fullExchangeName\":\"NasdaqGS\",\"instrumentType\":\"EQUITY\",\"firstTradeDate\":345479400,\"regularMarketTime\":1761854401,\"hasPrePostMarketData\":true,\"gmtoffset\":-14400,\"timezone\":\"EDT\",\"exchangeTimezoneName\":\"America/New_York\",\"regularMarketPrice\":271.4,\"longName\":\"Apple Inc.\",\"shortName\":\"Apple Inc.\",\"priceHint\":2,\"dataGranularity\":\"1d\",\"range\":\"1mo\"},\"timestamp\":[1754919000,1755005400],\"events\":{\"dividends\":{\"1754884800\":{\"amount\":0.26,\"date\":1754884800}},\"splits\":{\"1598880600\":{\"date\":1598880600,\"numerator\":4.0,\"denominator\":1.0,\"splitRatio\":\"4:1\"}}},\"indicators\":{\"quote\":[{\"open\":[227.9,228.0],\"high\":[229.5,230.8],\"low\":[227.0,227.1],\"close\":[227.2,229.6],\"volume\":[61806100,55626200]}],\"adjclose\":[{\"adjclose\":[226.9,229.3]}]}}],\"error\":null}}")

(deftest test-chart-meta-and-events
  (let [chart (:data (parse/parse-chart-body chart-with-events-json))]
    (testing "chart-meta keeps identity/exchange fields, kebab-cased"
      (is (= {:symbol "AAPL" :currency "USD" :exchange-name "NMS" :full-exchange-name "NasdaqGS"
              :instrument-type "EQUITY" :first-trade-date 345479400 :timezone "EDT"
              :exchange-timezone-name "America/New_York" :gmt-offset -14400 :price-hint 2
              :long-name "Apple Inc." :short-name "Apple Inc."}
             (parse/chart-meta chart))))

    (testing "chart-meta of a result without meta is empty"
      (is (= {} (parse/chart-meta {}))))

    (testing "chart-events returns Yahoo's keyword-epoch keyed maps"
      (is (= {:dividends {:1754884800 {:amount 0.26 :date 1754884800}}
              :splits {:1598880600 {:date 1598880600 :numerator 4.0 :denominator 1.0 :splitRatio "4:1"}}}
             (parse/chart-events chart))))

    (testing "chart-events without events yields empty maps"
      (is (= {:dividends {} :splits {}} (parse/chart-events (dissoc chart :events)))))))

(deftest test-fetch-historical-meta-and-events
  (let [queries (atom [])]
    (with-redefs [http/try-fetch* (fn [_ query]
                                    (swap! queries conj query)
                                    (parse/parse-chart-body chart-with-events-json))]
      (testing "default: :meta added, no :events, no events request param"
        (let [result (yf/fetch-historical* "AAPL" :period "1mo")]
          (is (:ok? result))
          (is (= 2 (count (:data result))))
          (is (= "America/New_York" (-> result :meta :exchange-timezone-name)))
          (is (= -14400 (-> result :meta :gmt-offset)))
          (is (not (contains? result :events)))
          (is (not (re-find #"events=" (last @queries))))))

      (testing ":events true requests div|split and returns them in fetch-dividends-splits shape"
        (let [result (yf/fetch-historical* "AAPL" :period "1mo" :events true)
              dividends-splits (yf/fetch-dividends-splits* "AAPL" :period "1mo")]
          (is (:ok? result))
          (is (re-find #"events=div%7Csplit" (last @queries)))
          (is (= (:data dividends-splits) (:events result)))
          (is (= 0.26 (-> result :events :dividends :1754884800 :amount)))))

      (testing ":events nil counts as false"
        (let [result (yf/fetch-historical* "AAPL" :period "1mo" :events nil)]
          (is (:ok? result))
          (is (not (contains? result :events)))))

      (testing "simple fetch-historical still returns rows only"
        (is (= 2 (count (yf/fetch-historical "AAPL" :period "1mo"))))
        (is (vector? (yf/fetch-historical "AAPL" :period "1mo"))))))

  (testing "non-boolean :events is rejected before any request"
    (with-redefs [http/try-fetch* (fn [& _] (throw (ex-info "no network in tests" {})))]
      (let [result (yf/fetch-historical* "AAPL" :events "div|split")]
        (is (false? (:ok? result)))
        (is (= :invalid-opts (-> result :error :type)))
        (is (= :events (-> result :error :field)))))))

(defn- mock-http-response [status body]
  (reify java.net.http.HttpResponse
    (statusCode [_] status)
    (body [_] body)))

(deftest test-chart-response-errors
  (let [parse #(#'http/parse-response* (mock-http-response %1 %2)
                                       {:ticker "AAPL" :url "u" :parse-fn #'http/chart-response})]
    (testing "non-200 is :http-error with status, ticker and url"
      (is (= {:ok? false :error {:type :http-error :status 404 :ticker "AAPL" :url "u"}}
             (parse 404 "{}"))))
    (testing "429 is :rate-limited"
      (is (= :rate-limited (-> (parse 429 "") :error :type))))
    (testing "200 with an API error keeps Yahoo's message and gains context"
      (is (= {:type :api-error :message "No data found" :ticker "AAPL" :status 200 :url "u"}
             (:error (parse 200 "{\"chart\":{\"error\":{\"description\":\"No data found\"}}}")))))))

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.core-test))
