(ns clj-yfinance.duckdb-test
  "Tests for DuckDB integration functions.

   Run with: clojure -M:test:duckdb -e \"(require 'clj-yfinance.duckdb-test) (clj-yfinance.duckdb-test/run-tests)\""
  (:require [clojure.test :refer :all]
            [clj-yfinance.duckdb :as yf-db]
            [tech.v3.dataset :as ds]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def sample-historical-data
  [{:timestamp 1704067200 :open 185.2 :high 186.1 :low 184.0
    :close 185.5 :volume 12345678 :adj-close 184.9}
   {:timestamp 1704153600 :open 186.0 :high 187.5 :low 185.8
    :close 187.2 :volume 15678901 :adj-close 186.6}
   {:timestamp 1704240000 :open 187.5 :high 188.9 :low 187.0
    :close 188.3 :volume 14567890 :adj-close 187.7}])

(def sample-multi-ticker-data
  (concat
   (map #(assoc % :ticker "AAPL") sample-historical-data)
   (map #(assoc % :ticker "GOOGL") sample-historical-data)))

(defn make-historical-ds []
  (ds/->dataset sample-historical-data
                {:parser-fn {:timestamp :int64
                             :open :float64 :high :float64
                             :low :float64 :close :float64
                             :volume :int64 :adj-close :float64}}))

(defn make-multi-ticker-ds []
  (ds/->dataset sample-multi-ticker-data
                {:parser-fn {:ticker :string
                             :timestamp :int64
                             :open :float64 :high :float64
                             :low :float64 :close :float64
                             :volume :int64 :adj-close :float64}}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest open-close-test
  (testing "open-db returns a map with :db and :conn keys"
    (let [db (yf-db/open-db)]
      (try
        (is (map? db) "open-db should return a map")
        (is (contains? db :db) "Result should have :db key")
        (is (contains? db :conn) "Result should have :conn key")
        (is (some? (:db db)) ":db should not be nil")
        (is (some? (:conn db)) ":conn should not be nil")
        (finally
          (yf-db/close! db))))))

(deftest load-dataset!-test
  (testing "load-dataset! inserts rows and returns row count"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (let [result (yf-db/load-dataset! db dataset)]
          (is (= 3 result) "Should return the number of rows inserted"))
        (finally
          (yf-db/close! db)))))

  (testing "load-dataset! respects :table-name option"
    (let [db (yf-db/open-db)
          dataset (make-historical-ds)]
      (try
        (yf-db/load-dataset! db dataset :table-name "my_table")
        (let [result (yf-db/query db "SELECT COUNT(*) AS n FROM my_table")]
          (is (= 3 (first (ds/column result :n))) "Should have 3 rows in named table"))
        (finally
          (yf-db/close! db))))))

(deftest query-test
  (testing "query returns a dataset"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (yf-db/load-dataset! db dataset)
        (let [result (yf-db/query db "SELECT * FROM AAPL ORDER BY timestamp")]
          (is (ds/dataset? result) "query should return a dataset")
          (is (= 3 (ds/row-count result)) "Should have 3 rows")
          (is (some #(= % :timestamp) (ds/column-names result)) "Should have timestamp column")
          (is (some #(= % :close) (ds/column-names result)) "Should have close column"))
        (finally
          (yf-db/close! db)))))

  (testing "query supports aggregation"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (yf-db/load-dataset! db dataset)
        (let [result (yf-db/query db "SELECT AVG(close) AS avg_close FROM AAPL")]
          (is (= 1 (ds/row-count result)) "Aggregation should return 1 row")
          (is (some #(= % :avg_close) (ds/column-names result)) "Should have avg_close column"))
        (finally
          (yf-db/close! db)))))

  (testing "query with LIMIT returns fewer rows"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (yf-db/load-dataset! db dataset)
        (let [result (yf-db/query db "SELECT * FROM AAPL LIMIT 1")]
          (is (= 1 (ds/row-count result)) "LIMIT 1 should return 1 row"))
        (finally
          (yf-db/close! db))))))

(deftest run!-test
  (testing "run! executes DDL without error"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (yf-db/load-dataset! db dataset)
        (yf-db/run! db "DROP TABLE IF EXISTS AAPL")
        (is true "DROP TABLE should not throw")
        (finally
          (yf-db/close! db))))))

(deftest multi-ticker-query-test
  (testing "Multi-ticker dataset supports GROUP BY queries"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-multi-ticker-ds) assoc :name "prices")]
      (try
        (yf-db/load-dataset! db dataset)
        (let [result (yf-db/query db "SELECT ticker, COUNT(*) AS n FROM prices GROUP BY ticker ORDER BY ticker")]
          (is (= 2 (ds/row-count result)) "Should have 2 rows (one per ticker)")
          (is (= ["AAPL" "GOOGL"] (vec (ds/column result :ticker))) "Should have both tickers")
          (is (= [3 3] (mapv long (ds/column result :n))) "Each ticker should have 3 rows"))
        (finally
          (yf-db/close! db))))))

(deftest data-round-trip-test
  (testing "Numeric values survive load → query round-trip"
    (let [db (yf-db/open-db)
          dataset (vary-meta (make-historical-ds) assoc :name "AAPL")]
      (try
        (yf-db/load-dataset! db dataset)
        (let [result (yf-db/query db "SELECT timestamp, close FROM AAPL ORDER BY timestamp")]
          (is (= [1704067200 1704153600 1704240000]
                 (mapv long (ds/column result :timestamp)))
              "Timestamps should round-trip")
          (is (= [185.5 187.2 188.3]
                 (mapv double (ds/column result :close)))
              "Close prices should round-trip"))
        (finally
          (yf-db/close! db))))))

;; ---------------------------------------------------------------------------

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.duckdb-test))

(comment
  (run-tests))
