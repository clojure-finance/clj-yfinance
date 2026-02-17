(ns clj-yfinance.experimental.auth
  "⚠️ EXPERIMENTAL: Authentication and session management for Yahoo Finance.
   
   This namespace handles cookie/crumb authentication for Yahoo's restricted
   endpoints (fundamentals, options, financial statements).
   
   WARNING: This is experimental and may break at any time. Yahoo can:
   - Change authentication at any time
   - Block or rate-limit requests
   - Detect automated access
   
   For production use, consider stable alternatives:
   - AlphaVantage: https://www.alphavantage.co/
   - Financial Modeling Prep: https://financialmodelingprep.com/
   - Polygon.io: https://polygon.io/
   
   Implementation notes:
   - Uses singleton session (thread-unsafe, single-user)
   - Cookies auto-refresh every hour
   - MAX 1 retry on authentication failure
   - Rate-limit protection via session reuse"
  (:import [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpClient$Redirect]
           [java.net URI CookieManager]
           [java.time Duration]))

;; Constants
(def ^:private cookie-url "https://fc.yahoo.com")
(def ^:private crumb-url "https://query2.finance.yahoo.com/v1/test/getcrumb")
(def ^:private user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
(def ^:private session-lifetime-ms (* 60 60 1000)) ; 1 hour in milliseconds

;; Session state (singleton)
(defonce ^:private session-state
  (atom {:http-client nil
         :cookie-handler nil
         :crumb nil
         :created-at nil
         :status :uninitialized}))

;; Logging
(defn- log [msg]
  (println (str "[clj-yfinance.experimental.auth] " msg)))

;; Session age check
(defn- session-age-ms [session]
  (if-let [created (:created-at session)]
    (- (System/currentTimeMillis) created)
    Long/MAX_VALUE))

(defn- session-expired? [session]
  (or (= :uninitialized (:status session))
      (= :failed (:status session))
      (nil? (:crumb session))
      (nil? (:cookie-handler session))
      (> (session-age-ms session) session-lifetime-ms)))

;; HTTP helpers
(defn- create-http-client []
  (let [cookie-handler (CookieManager.)]
    {:http-client (-> (HttpClient/newBuilder)
                      (.followRedirects HttpClient$Redirect/NORMAL)
                      (.connectTimeout (Duration/ofSeconds 10))
                      (.cookieHandler cookie-handler)
                      (.build))
     :cookie-handler cookie-handler}))

(defn- build-request [url]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create url))
      (.header "User-Agent" user-agent)
      (.timeout (Duration/ofSeconds 15))
      (.GET)
      (.build)))

(defn- send-request [client request]
  (.send client request (HttpResponse$BodyHandlers/ofString)))

;; Cookie acquisition
(defn- fetch-cookie! [client]
  (log "Fetching cookie from fc.yahoo.com...")
  (let [start (System/currentTimeMillis)
        request (build-request cookie-url)
        response (send-request client request)
        elapsed (- (System/currentTimeMillis) start)]
    (log (str "Cookie request completed in " elapsed "ms (status: " (.statusCode response) ")"))
    (when (not= 404 (.statusCode response))
      (log (str "Warning: Expected 404 from fc.yahoo.com, got " (.statusCode response))))
    response))

;; Crumb acquisition
(defn- fetch-crumb! [client cookie-handler]
  (log "Fetching crumb from getcrumb endpoint...")
  (let [start (System/currentTimeMillis)
        cookies (.getCookies (.getCookieStore cookie-handler))
        _ (when (zero? (count cookies))
            (throw (ex-info "No cookies available for crumb request"
                            {:type :no-cookies})))
        _ (log (str "Using " (count cookies) " cookie(s)"))
        request (build-request crumb-url)
        response (send-request client request)
        elapsed (- (System/currentTimeMillis) start)
        status (.statusCode response)
        body (.trim (.body response))]
    (log (str "Crumb request completed in " elapsed "ms (status: " status ")"))
    (if (= 200 status)
      (do
        (log (str "Crumb obtained: " body))
        body)
      (throw (ex-info "Failed to fetch crumb"
                      {:type :crumb-fetch-failed
                       :status status
                       :body body})))))

;; Session initialization and refresh
(defn- initialize-session! []
  (log "Initializing new Yahoo session...")
  (let [start (System/currentTimeMillis)]
    (try
      (let [{:keys [http-client cookie-handler]} (create-http-client)]
        ;; Step 1: Get cookie
        (fetch-cookie! http-client)

        ;; Step 2: Get crumb
        (let [crumb (fetch-crumb! http-client cookie-handler)
              elapsed (- (System/currentTimeMillis) start)]
          (log (str "Session initialized successfully in " elapsed "ms"))
          {:http-client http-client
           :cookie-handler cookie-handler
           :crumb crumb
           :created-at (System/currentTimeMillis)
           :status :active}))
      (catch Exception e
        (log (str "Session initialization FAILED: " (.getMessage e)))
        {:http-client nil
         :cookie-handler nil
         :crumb nil
         :created-at nil
         :status :failed
         :error e}))))

(defn- refresh-session! []
  (log "Refreshing Yahoo session (proactive or after 401)...")
  (let [new-session (initialize-session!)]
    (reset! session-state new-session)
    new-session))

;; Public API
(defn get-session
  "Returns current authenticated session, refreshing if needed.
   
   The session includes:
   - :http-client - Java HttpClient instance with cookies
   - :crumb - Authentication crumb for Yahoo API
   - :created-at - Session creation timestamp
   - :status - :active, :failed, or :uninitialized
   
   Sessions are automatically refreshed after 1 hour or on authentication failure."
  []
  (let [current @session-state]
    (if (session-expired? current)
      (do
        (log (str "Session expired (age: " (/ (session-age-ms current) 60000) " minutes), refreshing..."))
        (refresh-session!))
      current)))

(defn force-refresh!
  "Forcefully refresh the session (used after 401 errors).
   
   This is called automatically when authentication fails.
   MAX 1 retry per request to avoid hammering Yahoo's servers."
  []
  (refresh-session!))

(defn authenticated-request
  "Make an authenticated HTTP request to Yahoo Finance.
   
   Parameters:
   - url: Full URL string
   - retry-on-401?: If true, will attempt ONE session refresh on 401 error
   
   Returns:
   {:ok? true :response <HttpResponse>} or
   {:ok? false :error {...}}"
  [url & {:keys [retry-on-401?] :or {retry-on-401? true}}]
  (let [session (get-session)]
    (if (= :failed (:status session))
      {:ok? false
       :error {:type :session-failed
               :message "Session initialization failed"
               :exception (:error session)}}
      (try
        (let [request (build-request url)
              response (send-request (:http-client session) request)
              status (.statusCode response)]
          (if (and (= 401 status) retry-on-401?)
            (do
              (log "Got 401 error, attempting ONE session refresh...")
              (force-refresh!)
              ;; Retry ONCE with new session (retry-on-401? false prevents infinite loop)
              (authenticated-request url :retry-on-401? false))
            {:ok? true :response response}))
        (catch Exception e
          {:ok? false
           :error {:type :request-failed
                   :message (.getMessage e)
                   :exception e}})))))

(defn get-crumb
  "Get the current authentication crumb.
   
   Returns the crumb string or nil if session not initialized."
  []
  (:crumb @session-state))

(defn session-info
  "Get information about the current session (for debugging).
   
   Returns map with :status, :age-minutes, :crumb-present?"
  []
  (let [session @session-state]
    {:status (:status session)
     :age-minutes (if (:created-at session)
                    (/ (session-age-ms session) 60000)
                    nil)
     :crumb-present? (some? (:crumb session))}))
