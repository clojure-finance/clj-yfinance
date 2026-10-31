(ns clj-yfinance.experimental.earnings-test
  "Tests for the experimental earnings namespace.

   No network: fixtures are trimmed from real responses (2026-10-31), and the
   fetch functions run against stubbed quoteSummary / authenticated requests."
  (:require [charred.api :as charred]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-yfinance.experimental.auth :as auth]
            [clj-yfinance.experimental.earnings :as earnings]
            [clj-yfinance.experimental.fundamentals :as fundamentals]))

(defn- mock-response [status body]
  (reify java.net.http.HttpResponse
    (statusCode [_] status)
    (body [_] body)))

;; ---------------------------------------------------------------------------
;; Earnings history
;; ---------------------------------------------------------------------------

(def ^:private earnings-module-json
  "quoteSummary earnings module, quarters deliberately out of order; the last
   one lacks estimate, difference and report date."
  "{\"maxAge\":86400,\"financialCurrency\":\"USD\",\"earningsChart\":{\"quarterly\":[
     {\"date\":\"4Q2025\",\"actual\":{\"raw\":2.84,\"fmt\":\"2.84\"},\"estimate\":{\"raw\":2.6708,\"fmt\":\"2.67\"},\"fiscalQuarter\":\"1Q2026\",\"calendarQuarter\":\"4Q2025\",\"difference\":\"0.17\",\"surprisePct\":\"6.34\",\"periodEndDate\":{\"raw\":1767139200,\"fmt\":\"2025-12-31\"},\"reportedDate\":{\"raw\":1769722200,\"fmt\":\"2026-01-29\"}},
     {\"date\":\"3Q2025\",\"actual\":{\"raw\":1.85,\"fmt\":\"1.85\"},\"estimate\":{\"raw\":1.76993,\"fmt\":\"1.77\"},\"fiscalQuarter\":\"4Q2025\",\"calendarQuarter\":\"3Q2025\",\"difference\":\"0.08\",\"surprisePct\":\"4.52\",\"periodEndDate\":{\"raw\":1759190400,\"fmt\":\"2025-09-30\"},\"reportedDate\":{\"raw\":1761856200,\"fmt\":\"2025-10-30\"}},
     {\"date\":\"1Q2026\",\"actual\":{\"raw\":2.01,\"fmt\":\"2.01\"},\"fiscalQuarter\":\"2Q2026\",\"calendarQuarter\":\"1Q2026\",\"surprisePct\":\"\",\"periodEndDate\":{\"raw\":1774915200,\"fmt\":\"2026-03-31\"}}
   ],\"currentQuarterEstimate\":{\"raw\":1.97891,\"fmt\":\"1.98\"}}}")

(deftest earnings-history-rows-test
  (let [rows (earnings/earnings-history-rows "AAPL" (charred/read-json earnings-module-json {:key-fn keyword}))]
    (testing "rows sorted by period end"
      (is (= ["2025-09-30" "2025-12-31" "2026-03-31"] (map :period-end rows))))
    (testing "full row: strings parsed to doubles, report date and epoch kept"
      (is (= {:ticker "AAPL" :period-end "2025-09-30" :reported-date "2025-10-30"
              :reported-at 1761856200 :fiscal-quarter "4Q2025" :calendar-quarter "3Q2025"
              :eps-actual 1.85 :eps-estimate 1.76993 :eps-difference 0.08 :surprise-pct 4.52
              :currency "USD"}
             (first rows))))
    (testing "absent or blank values are nil, never invented"
      (is (= {:reported-date nil :reported-at nil :eps-estimate nil :eps-difference nil :surprise-pct nil}
             (select-keys (last rows) [:reported-date :reported-at :eps-estimate :eps-difference :surprise-pct])))
      (is (= 2.01 (:eps-actual (last rows))))))
  (testing "module without earningsChart gives no rows"
    (is (= [] (earnings/earnings-history-rows "X" {:maxAge 1})))
    (is (= [] (earnings/earnings-history-rows "X" nil)))))

(deftest fetch-earnings-history-test
  (testing "uses the earnings module and returns rows"
    (let [calls (atom [])]
      (with-redefs [fundamentals/fetch-quotesummary*
                    (fn [ticker modules]
                      (swap! calls conj [ticker modules])
                      {:ok? true :data {:earnings (charred/read-json earnings-module-json {:key-fn keyword})}})]
        (let [result (earnings/fetch-earnings-history* "AAPL")]
          (is (= [["AAPL" "earnings"]] @calls))
          (is (:ok? result))
          (is (= 3 (count (:data result))))
          (is (= {:ticker "AAPL" :modules "earnings"} (:request result))))
        (is (= 3 (count (earnings/fetch-earnings-history "AAPL")))))))

  (testing "errors pass through; simple variant returns []"
    (with-redefs [fundamentals/fetch-quotesummary*
                  (fn [ticker _] {:ok? false :error {:type :rate-limited :ticker ticker}})]
      (is (= :rate-limited (-> (earnings/fetch-earnings-history* "AAPL") :error :type)))
      (is (= [] (earnings/fetch-earnings-history "AAPL")))))

  (testing "blank ticker is :invalid-opts"
    (is (= :invalid-opts (-> (earnings/fetch-earnings-history* " ") :error :type)))))

;; ---------------------------------------------------------------------------
;; Earnings dates (visualization API)
;; ---------------------------------------------------------------------------

(def ^:private columns
  [{:id "ticker"} {:id "companyshortname"} {:id "eventname"} {:id "startdatetime"}
   {:id "startdatetimetype"} {:id "epsestimate"} {:id "epsactual"} {:id "epssurprisepct"}
   {:id "timeZoneShortName"} {:id "gmtOffsetMilliSeconds"}])

(def ^:private rows
  [["AAPL" "Apple Inc." nil "2025-05-01T20:30:00.000Z" "TAS" 1.63 1.65 1.41 "EDT" -14400000]
   ["AAPL" "Apple Inc" " Annual Shareholders Meeting" "2025-02-27T17:00:00.000Z" "TAS" nil nil nil "EST" -18000000]
   ["AAPL" "Apple Inc." "  " "2025-01-30T21:31:00.000Z" "TAS" 2.35 2.4 2.15 "EST" -18000000]])

(defn- visualization-json
  ([rows] (visualization-json columns rows (count rows)))
  ([columns rows total]
   (charred/write-json-str
    {:finance {:result [{:total total
                         :documents [{:entityIdType "EARNINGS" :columns columns :rows rows}]}]
               :error nil}})))

(deftest parse-visualization-response-test
  (testing "rows mapped by column id"
    (let [result (earnings/parse-visualization-response "AAPL" 200 (visualization-json rows))]
      (is (:ok? result))
      (is (= 3 (-> result :data :total)))
      (is (= {:ticker "AAPL" :company-name "Apple Inc." :event-name nil
              :announced-at "2025-05-01T20:30:00Z" :announced-epoch 1746131400
              :timing "TAS" :eps-estimate 1.63 :eps-actual 1.65 :surprise-pct 1.41
              :timezone "EDT" :gmt-offset-ms -14400000}
             (first (-> result :data :rows))))
      (testing "event names trimmed; blank becomes nil; null EPS stays nil"
        (is (= ["Annual Shareholders Meeting" nil]
               (map :event-name (rest (-> result :data :rows)))))
        (is (= [nil nil nil]
               ((juxt :eps-estimate :eps-actual :surprise-pct) (second (-> result :data :rows))))))))

  (testing "column order does not matter"
    (let [perm [9 3 0 7 1 5 2 8 4 6]
          shuffled-cols (mapv columns perm)
          shuffled-rows (mapv (fn [r] (mapv r perm)) rows)]
      (is (= (-> (earnings/parse-visualization-response "AAPL" 200 (visualization-json rows)) :data :rows)
             (-> (earnings/parse-visualization-response "AAPL" 200 (visualization-json shuffled-cols shuffled-rows 3))
                 :data :rows)))))

  (testing "missing columns give nil fields"
    (let [result (earnings/parse-visualization-response
                  "AAPL" 200 (visualization-json [{:id "startdatetime"}] [["2025-05-01T20:30:00.000Z"]] 1))]
      (is (= {:ticker "AAPL" :announced-epoch 1746131400 :eps-actual nil :timezone nil}
             (select-keys (first (-> result :data :rows)) [:ticker :announced-epoch :eps-actual :timezone])))))

  (testing "error responses"
    (is (= :api-error (-> (earnings/parse-visualization-response
                           "AAPL" 200 "{\"finance\":{\"result\":null,\"error\":{\"code\":\"x\",\"description\":\"bad\"}}}")
                          :error :type)))
    (is (= :missing-data (-> (earnings/parse-visualization-response
                              "AAPL" 200 "{\"finance\":{\"result\":[],\"error\":null}}")
                             :error :type)))
    (is (= :parse-error (-> (earnings/parse-visualization-response "AAPL" 200 "<html>") :error :type)))
    (is (= :auth-failed (-> (earnings/parse-visualization-response "AAPL" 401 "") :error :type)))
    (is (= :rate-limited (-> (earnings/parse-visualization-response "AAPL" 429 "") :error :type)))
    (let [e (:error (earnings/parse-visualization-response
                     "AAPL" 400 "{\"finance\":{\"result\":null,\"error\":{\"code\":\"Bad Request\",\"description\":\"size is too large\"}}}"))]
      (is (= :http-error (:type e)))
      (is (= 400 (:status e)))
      (is (str/includes? (:message e) "size is too large")))))

(defn- event
  "A visualization row for day `i` (later days have larger i)."
  [i]
  ["AAPL" "Apple Inc." nil (str (java.time.Instant/ofEpochSecond (* 86400 (+ 10000 i)))) "TAS" 1.0 1.0 0.0 "EST" -18000000])

(defn- stub-visualization
  "Stub auth/authenticated-request with a server holding `n` events, sorted
   descending like Yahoo. Returns an atom of the decoded request bodies."
  [n]
  (let [bodies (atom [])
        events (vec (map event (reverse (range n))))]
    [bodies
     (fn [url-fn & {:keys [method body]}]
       (assert (= :post method))
       (assert (str/includes? (url-fn "abc") "crumb=abc"))
       (let [{:strs [offset size] :as req} (charred/read-json body)]
         (swap! bodies conj req)
         {:ok? true
          :response (mock-response 200 (visualization-json columns
                                                           (subvec events (min offset n) (min (+ offset size) n))
                                                           n))}))]))

(deftest fetch-earnings-dates-test
  (testing "default limit is one page of 100, rows sorted ascending"
    (let [[bodies stub] (stub-visualization 127)]
      (with-redefs [auth/authenticated-request stub]
        (let [result (earnings/fetch-earnings-dates* "AAPL")]
          (is (:ok? result))
          (is (= 100 (count (:data result))))
          (is (= [{"offset" 0 "size" 100}] (map #(select-keys % ["offset" "size"]) @bodies)))
          (is (= ["ticker" "AAPL"] (get-in (first @bodies) ["query" "operands"])))
          (is (= (sort (map :announced-epoch (:data result))) (map :announced-epoch (:data result))))
          (is (= {:ticker "AAPL" :limit 100 :pages 1} (:request result)))))))

  (testing "limits above 250 are paged with offset, stopping at Yahoo's total"
    (let [[bodies stub] (stub-visualization 600)]
      (with-redefs [auth/authenticated-request stub]
        (let [result (earnings/fetch-earnings-dates* "AAPL" :limit 520)]
          (is (= [[0 250] [250 250] [500 20]] (map (juxt #(get % "offset") #(get % "size")) @bodies)))
          (is (= 520 (count (:data result))))
          (is (= 520 (count (distinct (map :announced-epoch (:data result))))))))))

  (testing "fewer events than the limit: stops after the short page"
    (let [[bodies stub] (stub-visualization 127)]
      (with-redefs [auth/authenticated-request stub]
        (is (= 127 (count (earnings/fetch-earnings-dates "AAPL" :limit 1000))))
        (is (= 1 (count @bodies))))))

  (testing "a failing page fails the call; simple variant returns []"
    (with-redefs [auth/authenticated-request
                  (fn [& _] {:ok? true :response (mock-response 429 "")})]
      (is (= :rate-limited (-> (earnings/fetch-earnings-dates* "AAPL") :error :type)))
      (is (= [] (earnings/fetch-earnings-dates "AAPL"))))
    (with-redefs [auth/authenticated-request
                  (fn [& _] {:ok? false :error {:type :session-failed}})]
      (is (= {:type :session-failed :ticker "AAPL"} (:error (earnings/fetch-earnings-dates* "AAPL"))))))

  (testing "option validation happens before any request"
    (with-redefs [auth/authenticated-request (fn [& _] (throw (ex-info "no network in tests" {})))]
      (is (= :limit (-> (earnings/fetch-earnings-dates* "AAPL" :limit 0) :error :field)))
      (is (= :limit (-> (earnings/fetch-earnings-dates* "AAPL" :limit 1.5) :error :field)))
      (is (= :opts (-> (earnings/fetch-earnings-dates* "AAPL" :size 10) :error :field)))
      (is (= :ticker (-> (earnings/fetch-earnings-dates* nil) :error :field))))))

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.earnings-test))

(comment
  (run-tests))
