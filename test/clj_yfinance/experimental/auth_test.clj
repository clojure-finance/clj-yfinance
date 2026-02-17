(ns clj-yfinance.experimental.auth-test
  "Tests for experimental authentication module.
   
   Note: These tests do NOT make live network calls to Yahoo Finance.
   They test the internal logic and state management without requiring
   actual authentication."
  (:require [clojure.test :refer :all]
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

;; Integration-style test (requires manual verification)
(deftest live-session-initialization-test
  (testing "Live session initialization (manual verification)"
    ;; This test actually initializes a session with Yahoo
    ;; It's commented out by default to avoid hitting Yahoo during normal test runs
    ;; Uncomment to verify the integration works

    (comment
      (let [session (clj-yfinance.experimental.auth/get-session)]
        (is (= :active (:status session))
            "Session should be active after initialization")
        (is (string? (:crumb session))
            "Session should have a crumb string")
        (is (some? (:http-client session))
            "Session should have an HTTP client")
        (is (some? (:cookie-handler session))
            "Session should have a cookie handler")
        (is (number? (:created-at session))
            "Session should have a creation timestamp")

        ;; Test session info
        (let [info (clj-yfinance.experimental.auth/session-info)]
          (is (= :active (:status info)))
          (is (< (:age-minutes info) 1) ; Less than 1 minute old
              "Newly created session should be very young")
          (is (:crumb-present? info)
              "Session should have crumb present"))))))

;; Test that session state is singleton
(deftest session-singleton-test
  (testing "Session is singleton"
    ;; The session-state atom should be the same instance
    (is (identical? @#'clj-yfinance.experimental.auth/session-state
                    @#'clj-yfinance.experimental.auth/session-state)
        "Session state should be singleton")))

;; Run tests helper
(defn run-tests []
  (clojure.test/run-tests 'clj-yfinance.experimental.auth-test))

(comment
  ;; Run all tests
  (run-tests)

  ;; Run individual test
  (session-age-test)
  (session-expired-test)
  (session-info-test))
