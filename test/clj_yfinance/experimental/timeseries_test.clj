(ns clj-yfinance.experimental.timeseries-test
  "Tests for the experimental fundamentals-timeseries namespace.

   No network: parsing is tested on JSON fixtures trimmed from real responses,
   and the fetch functions run against a stubbed http/get-with-failover*."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-yfinance.http :as http]
            [clj-yfinance.experimental.timeseries :as ts])
  (:import [java.net URLDecoder]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Fixtures (trimmed from real responses, 2026-10-31)
;; ---------------------------------------------------------------------------

(def ^:private nvda-json
  "Two series with values, one null entry, one entry without a raw value, and
   one type Yahoo returned without data (meta only)."
  "{\"timeseries\":{\"result\":[
    {\"meta\":{\"symbol\":[\"NVDA\"],\"type\":[\"quarterlyDilutedEPS\"]},\"timestamp\":[1753920000,1761868800,1769817600],
     \"quarterlyDilutedEPS\":[{\"dataId\":29009,\"asOfDate\":\"2025-07-31\",\"periodType\":\"3M\",\"currencyCode\":\"USD\",\"reportedValue\":{\"raw\":1.08,\"fmt\":\"1.08\"}},
                              null,
                              {\"dataId\":29009,\"asOfDate\":\"2026-01-31\",\"periodType\":\"3M\",\"currencyCode\":\"USD\",\"reportedValue\":{\"fmt\":\"-\"}}]},
    {\"meta\":{\"symbol\":[\"NVDA\"],\"type\":[\"annualTotalRevenue\"]},\"timestamp\":[1706659200,1738281600],
     \"annualTotalRevenue\":[{\"dataId\":20100,\"asOfDate\":\"2024-01-31\",\"periodType\":\"12M\",\"currencyCode\":\"USD\",\"reportedValue\":{\"raw\":60922000000,\"fmt\":\"60.92B\"}},
                             {\"dataId\":20100,\"asOfDate\":\"2025-01-31\",\"periodType\":\"12M\",\"currencyCode\":\"USD\",\"reportedValue\":{\"raw\":1.30497E11,\"fmt\":\"130.50B\"}}]},
    {\"meta\":{\"symbol\":[\"NVDA\"],\"type\":[\"annualGrossProfit\"]}}],
   \"error\":null}}")

(def ^:private no-types-json
  "{\"timeseries\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No timeseries type(s) specified\"}}}")

(def ^:private api-error-json
  "{\"timeseries\":{\"result\":null,\"error\":{\"code\":\"Bad Request\",\"description\":\"Invalid period\"}}}")

(defn- series-json
  "A 200 body with one value per type, for the stubbed HTTP layer."
  [types]
  (str "{\"timeseries\":{\"result\":["
       (str/join ","
                 (for [t types]
                   (format "{\"meta\":{\"symbol\":[\"X\"],\"type\":[\"%s\"]},\"%s\":[{\"asOfDate\":\"2025-06-30\",\"periodType\":\"3M\",\"currencyCode\":\"USD\",\"reportedValue\":{\"raw\":1}}]}"
                           t t)))
       "],\"error\":null}}"))

(defn- url-types
  "The type list of a timeseries request URL."
  [url]
  (let [query (second (str/split url #"\?" 2))
        params (into {} (map #(let [[k v] (str/split % #"=" 2)]
                                [k (URLDecoder/decode v "UTF-8")])
                             (str/split query #"&")))]
    (str/split (get params "type") #",")))

(defmacro ^:private with-stub-http
  "Run body with http/get-with-failover* answering each request through
   (respond types) -> [status body]; binds `calls` to an atom of request URL
   vectors."
  [[calls respond] & body]
  `(let [~calls (atom [])
         respond# ~respond]
     (with-redefs [http/get-with-failover*
                   (fn [urls# {parse-fn# :parse-fn}]
                     (swap! ~calls conj urls#)
                     (let [[status# body#] (respond# (url-types (first urls#)))]
                       (parse-fn# status# body#)))]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Catalog and field->keyword
;; ---------------------------------------------------------------------------

(deftest catalog-test
  (let [all (mapcat ts/statement-fields [:income-statement :balance-sheet :cash-flow])]
    (testing "statement-fields holds the three vectors"
      (is (= ts/income-statement-fields (:income-statement ts/statement-fields)))
      (is (= ts/balance-sheet-fields (:balance-sheet ts/statement-fields)))
      (is (= ts/cash-flow-fields (:cash-flow ts/statement-fields))))
    (testing "catalog is complete and has no duplicates"
      (is (= [103 149 123] (map count [ts/income-statement-fields ts/balance-sheet-fields ts/cash-flow-fields])))
      (is (= (count all) (count (distinct all)))))
    (testing "well-known fields are where expected"
      (is (some #{"TotalRevenue"} ts/income-statement-fields))
      (is (some #{"TotalAssets"} ts/balance-sheet-fields))
      (is (some #{"FreeCashFlow"} ts/cash-flow-fields)))
    (testing "field->keyword is injective over the whole catalog"
      (is (= (count all) (count (distinct (map ts/field->keyword all))))))))

(deftest field->keyword-test
  (is (= :total-revenue (ts/field->keyword "TotalRevenue")))
  (is (= :diluted-eps (ts/field->keyword "DilutedEPS")))
  (is (= :ebitda (ts/field->keyword "EBITDA")))
  (is (= :net-ppe (ts/field->keyword "NetPPE")))
  (is (= :net-ppe-purchase-and-sale (ts/field->keyword "NetPPEPurchaseAndSale")))
  (is (= :diluted-ni-availto-com-stockholders (ts/field->keyword "DilutedNIAvailtoComStockholders")))
  (is (= :interest-paid-cff (ts/field->keyword "InterestPaidCFF")))
  (is (= :diluted-eps-other-gains-losses (ts/field->keyword "DilutedEPSOtherGainsLosses"))))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(deftest parse-timeseries-body-test
  (testing "rows for every entry with a raw value, in response order"
    (let [result (ts/parse-timeseries-body "NVDA" nvda-json)]
      (is (:ok? result))
      (is (= [{:ticker "NVDA" :type "quarterlyDilutedEPS" :period-end "2025-07-31"
               :period-type "3M" :currency "USD" :value 1.08}
              {:ticker "NVDA" :type "annualTotalRevenue" :period-end "2024-01-31"
               :period-type "12M" :currency "USD" :value 6.0922E10}
              {:ticker "NVDA" :type "annualTotalRevenue" :period-end "2025-01-31"
               :period-type "12M" :currency "USD" :value 1.30497E11}]
             (:data result)))
      (is (every? double? (map :value (:data result))) "integer raws become doubles")))

  (testing "a result without any series gives no rows"
    (is (= {:ok? true :data []}
           (ts/parse-timeseries-body "X" "{\"timeseries\":{\"result\":[{\"meta\":{\"symbol\":[\"X\"],\"type\":[\"annualTotalRevenue\"]}}],\"error\":null}}"))))

  (testing "Yahoo error in the body is :api-error with Yahoo's message"
    (let [result (ts/parse-timeseries-body "X" api-error-json)]
      (is (false? (:ok? result)))
      (is (= :api-error (-> result :error :type)))
      (is (= "Invalid period" (-> result :error :message)))))

  (testing "missing result is :api-error"
    (is (= :api-error (-> (ts/parse-timeseries-body "X" "{\"timeseries\":{\"error\":null}}") :error :type))))

  (testing "invalid JSON is :parse-error"
    (is (= :parse-error (-> (ts/parse-timeseries-body "X" "<html>") :error :type)))))

(deftest parse-timeseries-response-test
  (testing "200 is parsed"
    (is (= 3 (count (:data (ts/parse-timeseries-response "NVDA" 200 nvda-json))))))
  (testing "404 'No timeseries type(s) specified' is an empty success, flagged"
    (is (= {:ok? true :data [] :no-known-types? true}
           (ts/parse-timeseries-response "X" 404 no-types-json))))
  (testing "other 404s and other statuses are :http-error"
    (is (= :http-error (-> (ts/parse-timeseries-response "X" 404 "not json") :error :type)))
    (is (= :http-error (-> (ts/parse-timeseries-response "X" 500 "") :error :type)))))

(deftest statements-rows-test
  (let [type-info (into {} (#'ts/statement-types ["DilutedEPS" "TotalAssets" "TotalRevenue"]
                                                 #{:annual :quarterly :trailing}))
        rows [{:type "quarterlyTotalRevenue" :period-end "2025-06-30" :period-type "3M" :currency "USD" :value 2.0}
              {:type "annualTotalAssets" :period-end "2024-09-30" :period-type "12M" :currency "USD" :value 3.0}
              {:type "quarterlyTotalRevenue" :period-end "2025-03-31" :period-type "3M" :currency "USD" :value 1.0}
              {:type "annualDilutedEPS" :period-end "2024-09-30" :period-type "12M" :currency "USD" :value 6.1}
              {:type "trailingDilutedEPS" :period-end "2025-06-30" :period-type "TTM" :currency "USD" :value 6.6}
              {:type "annualTotalRevenue" :period-end "2024-09-30" :period-type "12M" :currency "USD" :value 9.0}
              {:type "annualSomethingElse" :period-end "2024-09-30" :period-type "12M" :currency "USD" :value 0.0}]
        result (ts/statements-rows "AAPL" type-info rows)]
    (testing "trailing types exist for income-statement fields, not balance-sheet fields"
      (is (contains? type-info "trailingDilutedEPS"))
      (is (not (contains? type-info "trailingTotalAssets")))
      (is (= 8 (count type-info))))
    (testing "rows carry statement, field, frequency; unknown types are dropped"
      (is (= {:ticker "AAPL" :statement :income-statement :field :total-revenue
              :yahoo-field "TotalRevenue" :frequency :quarterly :period-end "2025-03-31"
              :period-type "3M" :currency "USD" :value 1.0}
             (first (filter #(= 1.0 (:value %)) result))))
      (is (= 6 (count result))))
    (testing "sorted by statement, field, frequency, period end"
      (is (= [[:income-statement :diluted-eps :annual "2024-09-30"]
              [:income-statement :diluted-eps :trailing "2025-06-30"]
              [:income-statement :total-revenue :annual "2024-09-30"]
              [:income-statement :total-revenue :quarterly "2025-03-31"]
              [:income-statement :total-revenue :quarterly "2025-06-30"]
              [:balance-sheet :total-assets :annual "2024-09-30"]]
             (map (juxt :statement :field :frequency :period-end) result))))))

;; ---------------------------------------------------------------------------
;; Option validation (fails before any request)
;; ---------------------------------------------------------------------------

(deftest option-validation-test
  (with-redefs [http/get-with-failover* (fn [& _] (throw (ex-info "no network in tests" {})))]
    (let [error-of (fn [result] (when-not (:ok? result) (-> result :error ((juxt :type :field)))))]
      (testing "fetch-statements* rejects unknown option values and keys"
        (is (= [:invalid-opts :frequencies] (error-of (ts/fetch-statements* "AAPL" :frequencies [:monthly]))))
        (is (= [:invalid-opts :frequencies] (error-of (ts/fetch-statements* "AAPL" :frequencies :annual))))
        (is (= [:invalid-opts :frequencies] (error-of (ts/fetch-statements* "AAPL" :frequencies []))))
        (is (= [:invalid-opts :statements] (error-of (ts/fetch-statements* "AAPL" :statements [:income]))))
        (is (= [:invalid-opts :fields] (error-of (ts/fetch-statements* "AAPL" :fields ["TotalRevenue" "Revenue"]))))
        (is (= ["Revenue"] (:value (:error (ts/fetch-statements* "AAPL" :fields ["TotalRevenue" "Revenue"])))))
        (is (= [:invalid-opts :fields] (error-of (ts/fetch-statements* "AAPL" :fields "TotalRevenue"))))
        (is (= [:invalid-opts :opts] (error-of (ts/fetch-statements* "AAPL" :frequency [:annual]))))
        (is (= [:invalid-opts :start] (error-of (ts/fetch-statements* "AAPL" :start "2020-01-01"))))
        (is (= [:invalid-opts :start/end] (error-of (ts/fetch-statements* "AAPL" :start 200 :end 100))))
        (is (= [:invalid-opts :ticker] (error-of (ts/fetch-statements* "")))))
      (testing "fetch-timeseries* validates types"
        (is (= [:invalid-opts :types] (error-of (ts/fetch-timeseries* "AAPL" []))))
        (is (= [:invalid-opts :types] (error-of (ts/fetch-timeseries* "AAPL" "annualTotalRevenue"))))
        (is (= [:invalid-opts :types] (error-of (ts/fetch-timeseries* "AAPL" ["annualTotalRevenue" nil])))))
      (testing "simple variants return [] on invalid options"
        (is (= [] (ts/fetch-statements "AAPL" :frequencies [:monthly])))
        (is (= [] (ts/fetch-timeseries "AAPL" [])))))))

;; ---------------------------------------------------------------------------
;; Fetching with a stubbed HTTP layer
;; ---------------------------------------------------------------------------

(deftest chunking-test
  (testing "types are sent in chunks of at most 100, query2 first, deduplicated"
    (let [types (concat (map #(str "annualT" %) (range 250)) ["annualT0"])]
      (with-stub-http [calls (fn [types] [200 (series-json types)])]
        (let [result (ts/fetch-timeseries* "AAPL" types :start (Instant/ofEpochSecond 1000) :end 2000)]
          (is (:ok? result))
          (is (= [100 100 50] (map (comp count url-types first) @calls)))
          (is (= 250 (count (:data result))))
          (is (= 3 (-> result :request :chunks)))
          (is (= {:period1 1000 :period2 2000} (-> result :request :query-params)))
          (is (every? #(str/starts-with? (first %) "https://query2.") @calls))
          (is (every? #(str/starts-with? (second %) "https://query1.") @calls))
          (is (str/includes? (ffirst @calls) "period1=1000&period2=2000"))
          (is (= (sort-by (juxt :type :period-end) (:data result)) (:data result)) "rows sorted by type"))))))

(deftest partial-failure-test
  (testing "a failing chunk fails the whole call, without partial data"
    (with-stub-http [calls (fn [types] (if (some #{"annualT150"} types)
                                         [503 ""]
                                         [200 (series-json types)]))]
      (let [result (ts/fetch-timeseries* "AAPL" (map #(str "annualT" %) (range 250)))]
        (is (false? (:ok? result)))
        (is (= :http-error (-> result :error :type)))
        (is (nil? (:data result)))
        (is (= 2 (count @calls)) "stops at the failing chunk")))))

(deftest unknown-types-test
  (testing "a chunk of only unknown types adds no rows; the others still count"
    (with-stub-http [_ (fn [types] (if (some #{"trailingT0"} types)
                                     [404 no-types-json]
                                     [200 (series-json types)]))]
      (let [result (ts/fetch-timeseries* "AAPL" (concat (map #(str "annualT" %) (range 100))
                                                        ["trailingT0"]))]
        (is (:ok? result))
        (is (= 100 (count (:data result)))))))

  (testing "when Yahoo knows none of the types, fetch-timeseries* fails with :no-known-types"
    (with-stub-http [_ (constantly [404 no-types-json])]
      (let [result (ts/fetch-timeseries* "AAPL" ["annualNoSuchField"])]
        (is (false? (:ok? result)))
        (is (= :api-error (-> result :error :type)))
        (is (= :no-known-types (-> result :error :reason))))))

  (testing "fetch-statements* turns that into an empty success"
    (with-stub-http [_ (constantly [404 no-types-json])]
      (is (= {:ok? true :data []}
             (select-keys (ts/fetch-statements* "AAPL" :fields ["ForeignSales"] :frequencies [:trailing])
                          [:ok? :data]))))))

(deftest fetch-statements-test
  (testing "requests one type per field and frequency and returns statement rows"
    (with-stub-http [calls (fn [types] [200 (series-json types)])]
      (let [result (ts/fetch-statements* "AAPL" :fields ["TotalRevenue" "TotalAssets"])]
        (is (:ok? result))
        (is (= #{"annualTotalRevenue" "quarterlyTotalRevenue" "trailingTotalRevenue"
                 "annualTotalAssets" "quarterlyTotalAssets"}
               (set (url-types (ffirst @calls)))))
        (is (= [[:income-statement :total-revenue :annual]
                [:income-statement :total-revenue :quarterly]
                [:income-statement :total-revenue :trailing]
                [:balance-sheet :total-assets :annual]
                [:balance-sheet :total-assets :quarterly]]
               (map (juxt :statement :field :frequency) (:data result))))
        (is (= "AAPL" (:ticker (first (:data result))))))))

  (testing "default requests every statement at every frequency (no trailing balance sheet)"
    (with-stub-http [calls (fn [types] [200 (series-json types)])]
      (let [result (ts/fetch-statements* "AAPL")
            n (+ (* 3 103) (* 2 149) (* 3 123))]
        (is (:ok? result))
        (is (= n (count (mapcat (comp url-types first) @calls))))
        (is (= (int (Math/ceil (/ n 100.0))) (count @calls)))
        (is (= n (count (:data result)))))))

  (testing "statements and frequencies narrow the request"
    (with-stub-http [calls (fn [types] [200 (series-json types)])]
      (ts/fetch-statements* "AAPL" :statements [:balance-sheet] :frequencies [:annual])
      (is (= (map #(str "annual" %) ts/balance-sheet-fields)
             (mapcat (comp url-types first) @calls)))))

  (testing "only trailing balance sheet asks Yahoo nothing and returns no rows"
    (with-stub-http [calls (fn [types] [200 (series-json types)])]
      (is (= [] (ts/fetch-statements "AAPL" :statements [:balance-sheet] :frequencies [:trailing])))
      (is (empty? @calls)))))

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.timeseries-test))

(comment
  (run-tests))
