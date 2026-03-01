(ns clj-yfinance.parquet-test
  "Tests for Parquet save/load functions.

   Run with: clojure -M:test:parquet -e \"(require 'clj-yfinance.parquet-test) (clj-yfinance.parquet-test/run-tests)\""
  (:require [clojure.test :refer :all]
            [clj-yfinance.parquet :as yfp]
            [tech.v3.dataset :as ds])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Fixtures

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

(defn tmp-path [filename]
  (str (Files/createTempDirectory "clj-yfinance-test" (make-array FileAttribute 0))
       "/" filename))

;; Tests

(deftest save-dataset!-test
  (testing "Save and reload an arbitrary dataset"
    (let [path (tmp-path "test.parquet")
          ds-in (ds/->dataset sample-historical-data
                              {:parser-fn {:timestamp :int64
                                           :open :float64 :high :float64
                                           :low :float64 :close :float64
                                           :volume :int64 :adj-close :float64}})
          returned (yfp/save-dataset! ds-in path)
          ds-out (yfp/load-dataset path)]
      (is (= ds-in returned) "save-dataset! should return the dataset")
      (is (= (ds/row-count ds-in) (ds/row-count ds-out)) "Row count should round-trip")
      (is (= (set (ds/column-names ds-in)) (set (ds/column-names ds-out))) "Columns should round-trip")
      (is (= (vec (ds/column ds-in :timestamp)) (vec (ds/column ds-out :timestamp))) "Timestamps should round-trip")
      (is (= (vec (ds/column ds-in :close)) (vec (ds/column ds-out :close))) "Close prices should round-trip"))))

(deftest load-historical-test
  (testing "load-historical reads a parquet file as a dataset"
    (let [path (tmp-path "hist.parquet")
          ds-in (ds/->dataset sample-historical-data
                              {:parser-fn {:timestamp :int64
                                           :open :float64 :high :float64
                                           :low :float64 :close :float64
                                           :volume :int64 :adj-close :float64}})]
      (yfp/save-dataset! ds-in path)
      (let [ds-out (yfp/load-historical path)]
        (is (= 3 (ds/row-count ds-out)))
        (is (= (vec (ds/column ds-in :timestamp)) (vec (ds/column ds-out :timestamp))))
        (is (= (vec (ds/column ds-in :close)) (vec (ds/column ds-out :close))))))))

(deftest multi-ticker-round-trip-test
  (testing "Multi-ticker dataset round-trips through parquet"
    (let [path (tmp-path "multi.parquet")
          ds-in (ds/->dataset sample-multi-ticker-data
                              {:parser-fn {:ticker :string
                                           :timestamp :int64
                                           :open :float64 :high :float64
                                           :low :float64 :close :float64
                                           :volume :int64 :adj-close :float64}})
          _ (yfp/save-dataset! ds-in path)
          ds-out (yfp/load-dataset path)]
      (is (= 6 (ds/row-count ds-out)) "Should have 6 rows (3 per ticker)")
      (is (= #{"AAPL" "GOOGL"} (set (ds/column ds-out :ticker))) "Both tickers should be present")
      (is (= (vec (ds/column ds-in :timestamp)) (vec (ds/column ds-out :timestamp))) "Timestamps should round-trip"))))

(deftest column-types-preserved-test
  (testing "Column types are preserved through parquet round-trip"
    (let [path (tmp-path "types.parquet")
          ds-in (ds/->dataset sample-historical-data
                              {:parser-fn {:timestamp :int64
                                           :open :float64 :high :float64
                                           :low :float64 :close :float64
                                           :volume :int64 :adj-close :float64}})]
      (yfp/save-dataset! ds-in path)
      (let [ds-out (yfp/load-historical path)
            col-type #(-> ds-out (ds/column %) meta :datatype)]
        (is (= :int64 (col-type :timestamp)))
        (is (= :float64 (col-type :open)))
        (is (= :float64 (col-type :close)))
        (is (= :int64 (col-type :volume)))))))

(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.parquet-test))

(comment
  (run-tests))
