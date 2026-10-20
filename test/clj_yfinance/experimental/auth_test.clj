(ns clj-yfinance.experimental.auth-test
  "Tests for experimental authentication module.
   
   Note: These tests do NOT make live network calls to Yahoo Finance.
   They test the internal logic and state management without requiring
   actual authentication."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-yfinance.experimental.auth]))

;; Test session age calculation
(deftest session-age-test
  (testing "Session age calculation"
    (let [now (System/currentTimeMillis)
          session-1hr-old {:created-at (- now (* 60 60 1000))}
          session-new {:created-at now}
          session-no-timestamp {:created-at nil}]

      ;; 1 hour old session
      (is (>= (#'clj-yfinance.experimental.auth/session-age-ms session-1hr-old)
              (* 60 60 1000))
          "1-hour old session should have age >= 1 hour")

      ;; New session
      (is (< (#'clj-yfinance.experimental.auth/session-age-ms session-new)
             1000)
          "New session should have age < 1 second")

      ;; No timestamp
      (is (= Long/MAX_VALUE
             (#'clj-yfinance.experimental.auth/session-age-ms session-no-timestamp))
          "Session without timestamp should return MAX_VALUE"))))

(deftest session-expired-test
  (testing "Session expiration detection"
    (let [now (System/currentTimeMillis)]

      ;; Uninitialized session
      (is (#'clj-yfinance.experimental.auth/session-expired?
           {:status :uninitialized})
          "Uninitialized session should be considered expired")

      ;; Failed session
      (is (#'clj-yfinance.experimental.auth/session-expired?
           {:status :failed})
          "Failed session should be considered expired")

      ;; Missing crumb
      (is (#'clj-yfinance.experimental.auth/session-expired?
           {:status :active
            :crumb nil
            :cookie-handler :mock
            :created-at now})
          "Session without crumb should be expired")

      ;; Missing cookie-handler
      (is (#'clj-yfinance.experimental.auth/session-expired?
           {:status :active
            :crumb "abc123"
            :cookie-handler nil
            :created-at now})
          "Session without cookie-handler should be expired")

      ;; Old session (over 1 hour)
      (is (#'clj-yfinance.experimental.auth/session-expired?
           {:status :active
            :crumb "abc123"
            :cookie-handler :mock
            :created-at (- now (* 2 60 60 1000))}) ; 2 hours ago
          "Session older than 1 hour should be expired")

      ;; Valid active session
      (is (not (#'clj-yfinance.experimental.auth/session-expired?
                {:status :active
                 :crumb "abc123"
                 :cookie-handler :mock
                 :created-at now}))
          "Valid active session should not be expired"))))

(deftest session-info-test
  (testing "Session info reporting"
    ;; Active session with all data
    (let [now (System/currentTimeMillis)
          info (#'clj-yfinance.experimental.auth/session-info)]
      (is (map? info) "session-info should return a map")
      (is (contains? info :status) "Info should contain :status")
      (is (contains? info :age-minutes) "Info should contain :age-minutes")
      (is (contains? info :crumb-present?) "Info should contain :crumb-present?"))))

(deftest authenticated-request-401-retry-test
  (testing "After a 401 the retry rebuilds the URL with the refreshed crumb"
    (let [session (atom {:status :active :crumb "old" :http-client :mock})
          urls (atom [])]
      (with-redefs [clj-yfinance.experimental.auth/get-session (fn [] @session)
                    clj-yfinance.experimental.auth/refresh-session!
                    (fn [] (swap! session assoc :crumb "new"))
                    clj-yfinance.experimental.auth/send-request
                    (fn [_ request]
                      (let [url (str (.uri ^java.net.http.HttpRequest request))]
                        (swap! urls conj url)
                        (reify java.net.http.HttpResponse
                          (statusCode [_] (if (.endsWith url "old") 401 200)))))]
        (let [result (clj-yfinance.experimental.auth/authenticated-request
                      #(str "https://example.com/x?crumb=" %))]
          (is (:ok? result))
          (is (= 200 (.statusCode (:response result))))
          (is (= ["https://example.com/x?crumb=old" "https://example.com/x?crumb=new"]
                 @urls))))))

  (testing "Only one refresh: a second 401 is returned to the caller"
    (let [refreshes (atom 0)]
      (with-redefs [clj-yfinance.experimental.auth/get-session
                    (fn [] {:status :active :crumb "c" :http-client :mock})
                    clj-yfinance.experimental.auth/refresh-session! (fn [] (swap! refreshes inc))
                    clj-yfinance.experimental.auth/send-request
                    (fn [_ _] (reify java.net.http.HttpResponse (statusCode [_] 401)))]
        (let [result (clj-yfinance.experimental.auth/authenticated-request "https://example.com/x")]
          (is (= 1 @refreshes))
          (is (:ok? result))
          (is (= 401 (.statusCode (:response result)))))))))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.auth-test))

(comment
  ;; Run all tests
  (run-tests)

  ;; Manual check against live Yahoo (not part of the suite: tests stay offline)
  (let [session (clj-yfinance.experimental.auth/get-session)]
    [(:status session) (string? (:crumb session))
     (clj-yfinance.experimental.auth/session-info)]))
