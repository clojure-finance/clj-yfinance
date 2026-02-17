(ns clj-yfinance.dataset-test
  "Tests for dataset conversion functions.
   
   Run with: clojure -M:test:dataset -e \"(require 'clj-yfinance.dataset-test) (clj-yfinance.dataset-test/run-tests)\""
  (:require [clojure.test :refer :all]
            [clj-yfinance.dataset :as yfd]
            [tech.v3.dataset :as ds]))

;; Test fixtures - sample data matching what core functions return

(def sample-historical-data
  [{:timestamp 1704067200 :open 185.2 :high 186.1 :low 184.0
    :close 185.5 :volume 12345678 :adj-close 184.9}
   {:timestamp 1704153600 :open 186.0 :high 187.5 :low 185.8
    :close 187.2 :volume 15678901 :adj-close 186.6}
   {:timestamp 1704240000 :open 187.5 :high 188.9 :low 187.0
    :close 188.3 :volume 14567890 :adj-close 187.7}])

(def sample-prices
  {"AAPL" 261.05
   "GOOGL" 337.28
   "MSFT" 428.04})

(def sample-dividends-splits
  {:dividends {1699574400 {"amount" 0.24 "date" 1699574400}
               1707235200 {"amount" 0.25 "date" 1707235200}}
   :splits {1598832000 {"numerator" 4 "denominator" 1 "date" 1598832000}}})

(def sample-info
  {:symbol "AAPL"
   :long-name "Apple Inc."
   :currency "USD"
   :exchange-name "NMS"
   :regular-market-price 261.05
   :regular-market-volume 92443408
   :fifty-two-week-high 288.62
   :fifty-two-week-low 169.21})

;; Tests

(deftest historical->dataset-test
  (testing "Convert historical data to dataset"
    (let [result (yfd/historical->dataset sample-historical-data)]
      (is (some? result) "Should return a dataset")
      (is (= 3 (ds/row-count result)) "Should have 3 rows")
      (is (= [:timestamp :open :high :low :close :volume :adj-close]
             (ds/column-names result))
          "Should have correct columns in order")

      ;; Check column types
      (is (= :int64 (-> result (ds/column :timestamp) meta :datatype))
          "Timestamp should be int64")
      (is (= :float64 (-> result (ds/column :open) meta :datatype))
          "Open should be float64")
      (is (= :int64 (-> result (ds/column :volume) meta :datatype))
          "Volume should be int64")

      ;; Check values
      (is (= [1704067200 1704153600 1704240000]
             (vec (ds/column result :timestamp)))
          "Timestamps should match")
      (is (= [185.5 187.2 188.3]
             (vec (ds/column result :close)))
          "Close prices should match")))

  (testing "Empty data returns nil"
    (is (nil? (yfd/historical->dataset [])))
    (is (nil? (yfd/historical->dataset nil)))))

(deftest prices->dataset-test
  (testing "Convert prices map to dataset"
    (let [result (yfd/prices->dataset sample-prices)]
      (is (some? result) "Should return a dataset")
      (is (= 3 (ds/row-count result)) "Should have 3 rows")
      (is (= [:ticker :price] (ds/column-names result))
          "Should have ticker and price columns")

      ;; Check column types
      (is (= :string (-> result (ds/column :ticker) meta :datatype))
          "Ticker should be string")
      (is (= :float64 (-> result (ds/column :price) meta :datatype))
          "Price should be float64")

      ;; Check values (convert to set since map order is not guaranteed)
      (let [tickers (set (ds/column result :ticker))
            prices (set (ds/column result :price))]
        (is (= #{"AAPL" "GOOGL" "MSFT"} tickers)
            "Should have all tickers")
        (is (= #{261.05 337.28 428.04} prices)
            "Should have all prices"))))

  (testing "Empty prices returns nil"
    (is (nil? (yfd/prices->dataset {})))
    (is (nil? (yfd/prices->dataset nil)))))

(deftest dividends-splits->dataset-test
  (testing "Convert dividends and splits to datasets"
    (let [result (yfd/dividends-splits->dataset sample-dividends-splits)]
      (is (some? result) "Should return a map")
      (is (contains? result :dividends) "Should have dividends key")
      (is (contains? result :splits) "Should have splits key")

      ;; Check dividends dataset
      (let [div-ds (:dividends result)]
        (is (= 2 (ds/row-count div-ds)) "Should have 2 dividend rows")
        (is (= [:timestamp :amount] (ds/column-names div-ds))
            "Should have timestamp and amount columns")
        (is (= :int64 (-> div-ds (ds/column :timestamp) meta :datatype))
            "Timestamp should be int64")
        (is (= :float64 (-> div-ds (ds/column :amount) meta :datatype))
            "Amount should be float64")
        (is (= #{0.24 0.25} (set (ds/column div-ds :amount)))
            "Should have correct dividend amounts"))

      ;; Check splits dataset
      (let [split-ds (:splits result)]
        (is (= 1 (ds/row-count split-ds)) "Should have 1 split row")
        (is (= [:timestamp :numerator :denominator] (ds/column-names split-ds))
            "Should have timestamp, numerator, and denominator columns")
        (is (= :int64 (-> split-ds (ds/column :numerator) meta :datatype))
            "Numerator should be int64")
        (is (= [4] (vec (ds/column split-ds :numerator)))
            "Should have correct numerator"))))

  (testing "Only dividends returns only dividends dataset"
    (let [result (yfd/dividends-splits->dataset
                  {:dividends {1699574400 {"amount" 0.24}}
                   :splits {}})]
      (is (contains? result :dividends))
      (is (not (contains? result :splits)))))

  (testing "Only splits returns only splits dataset"
    (let [result (yfd/dividends-splits->dataset
                  {:dividends {}
                   :splits {1598832000 {"numerator" 4 "denominator" 1}}})]
      (is (not (contains? result :dividends)))
      (is (contains? result :splits))))

  (testing "Empty data returns nil"
    (is (nil? (yfd/dividends-splits->dataset {:dividends {} :splits {}})))
    (is (nil? (yfd/dividends-splits->dataset nil)))))

(deftest info->dataset-test
  (testing "Convert ticker info to single-row dataset"
    (let [result (yfd/info->dataset sample-info)]
      (is (some? result) "Should return a dataset")
      (is (= 1 (ds/row-count result)) "Should have 1 row")

      ;; Check that key columns are present
      (let [cols (set (ds/column-names result))]
        (is (contains? cols :symbol))
        (is (contains? cols :long-name))
        (is (contains? cols :currency))
        (is (contains? cols :regular-market-price)))

      ;; Check values
      (is (= "AAPL" (first (ds/column result :symbol)))
          "Should have correct symbol")
      (is (= "Apple Inc." (first (ds/column result :long-name)))
          "Should have correct long name")
      (is (= 261.05 (first (ds/column result :regular-market-price)))
          "Should have correct price")))

  (testing "Empty info returns nil"
    (is (nil? (yfd/info->dataset nil)))))

(deftest multi-ticker->dataset-test
  (testing "Multi-ticker data with ticker column"
    ;; Create multi-ticker data manually (simulating what the function would fetch)
    (let [aapl-data (map #(assoc % :ticker "AAPL") sample-historical-data)
          googl-data (map #(assoc % :ticker "GOOGL") sample-historical-data)
          combined-data (concat aapl-data googl-data)
          result (yfd/historical->dataset combined-data)]

      (is (some? result) "Should return a dataset")
      (is (= 6 (ds/row-count result)) "Should have 6 rows (3 per ticker)")

      ;; Check columns include ticker
      (let [cols (ds/column-names result)]
        (is (.contains cols :ticker) "Should have ticker column")
        (is (.contains cols :timestamp) "Should have timestamp column"))

      ;; Check ticker values
      (let [tickers (set (ds/column result :ticker))]
        (is (= #{"AAPL" "GOOGL"} tickers)
            "Should have both tickers")))))

;; Integration-style test helper
(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.dataset-test))

(comment
  ;; Run tests interactively
  (run-tests)

  ;; Test individual functions
  (historical->dataset-test)
  (prices->dataset-test)
  (dividends-splits->dataset-test)
  (info->dataset-test)
  (multi-ticker->dataset-test))
