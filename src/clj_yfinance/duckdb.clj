(ns clj-yfinance.duckdb
  "Optional DuckDB integration for clj-yfinance datasets.

   Loads datasets fetched via clj-yfinance into an embedded DuckDB instance
   and lets you query them with SQL.

   REQUIRES: com.techascent/tmducken and techascent/tech.ml.dataset on the classpath.

   DuckDB also requires a native shared library (libduckdb). On Linux you can
   install it via your package manager, or set the DUCKDB_HOME environment
   variable to the directory containing the library before starting your REPL.

   USAGE:
   (require '[clj-yfinance.duckdb :as yf-db])

   ;; Open an in-memory database and load some data
   (def db (yf-db/open-db))
   (yf-db/load-historical! db \"AAPL\" :period \"1y\")
   (yf-db/load-multi-ticker! db [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")

   ;; Query with SQL
   (yf-db/query db \"SELECT * FROM AAPL ORDER BY timestamp DESC LIMIT 5\")
   (yf-db/query db \"SELECT ticker, AVG(close) AS avg_close FROM prices GROUP BY ticker\")

   ;; Close when done
   (yf-db/close! db)"
  (:require [clj-yfinance.dataset :as yfd]
            [tech.v3.dataset :as ds]
            [tmducken.duckdb :as duckdb]))

(defn open-db
  "Open an embedded DuckDB database. Pass a file path to persist to disk,
   or omit for an in-memory database.

   Calls duckdb/initialize! automatically on first use.

   Returns a map {:db <db> :conn <conn>} that is passed to the other functions.

   Examples:
   (open-db)              ; in-memory
   (open-db \"finance.db\") ; persistent"
  ([] (open-db nil))
  ([path]
   (duckdb/initialize!)
   (let [db (duckdb/open-db path)
         conn (duckdb/connect db)]
     {:db db :conn conn})))

(defn close!
  "Disconnect and close a database opened with open-db.

   Example:
   (close! db)"
  [{:keys [db conn]}]
  (duckdb/disconnect conn)
  (duckdb/close-db db))

(defn load-dataset!
  "Load an arbitrary tech.v3.dataset into DuckDB under the given table name.

   The dataset must have a :name in its metadata, or you must supply :table-name
   via options. Creates the table and inserts all rows.

   Returns the number of rows inserted.

   Example:
   (load-dataset! db my-ds :table-name \"prices\")"
  [{:keys [conn]} dataset & {:keys [table-name] :as opts}]
  (let [named-ds (if table-name
                   (vary-meta dataset assoc :name table-name)
                   dataset)]
    (duckdb/create-table! conn named-ds)
    (duckdb/insert-dataset! conn named-ds)))

(defn load-historical!
  "Fetch historical OHLCV data for ticker and load into DuckDB.

   The table name defaults to the ticker symbol (e.g. \"AAPL\").
   Options are passed through to fetch-historical:
   :period, :interval, :start, :end, :auto-adjust, :prepost

   Returns the number of rows inserted, or nil if fetch fails.

   Example:
   (load-historical! db \"AAPL\" :period \"1y\")"
  [{:keys [conn]} ticker & opts]
  (when-let [dataset (apply yfd/historical->dataset ticker opts)]
    (let [named-ds (vary-meta dataset assoc :name ticker)]
      (duckdb/create-table! conn named-ds)
      (duckdb/insert-dataset! conn named-ds))))

(defn load-multi-ticker!
  "Fetch historical data for multiple tickers and load into DuckDB as a single table.

   The combined dataset (with a :ticker column) is loaded under the given table name,
   defaulting to \"prices\".

   Options are passed through to fetch-historical:
   :period, :interval, :start, :end, :auto-adjust, :prepost

   Returns the number of rows inserted, or nil if all fetches fail.

   Example:
   (load-multi-ticker! db [\"AAPL\" \"GOOGL\" \"MSFT\"] :period \"1y\")
   (query db \"SELECT ticker, AVG(close) FROM prices GROUP BY ticker\")"
  [{:keys [conn]} tickers & {:keys [table-name] :or {table-name "prices"} :as opts}]
  (let [fetch-opts (dissoc opts :table-name)]
    (when-let [dataset (apply yfd/multi-ticker->dataset tickers (apply concat fetch-opts))]
      (let [named-ds (vary-meta dataset assoc :name table-name)]
        (duckdb/create-table! conn named-ds)
        (duckdb/insert-dataset! conn named-ds)))))

(defn query
  "Run a SQL query against the database and return a dataset.

   Result column names are returned as keywords, consistent with the
   keyword-keyed datasets produced elsewhere in clj-yfinance (DuckDB itself
   reports them as strings).

   Example:
   (query db \"SELECT * FROM AAPL ORDER BY timestamp DESC LIMIT 10\")
   (query db \"SELECT ticker, AVG(close) AS avg_close FROM prices GROUP BY ticker ORDER BY avg_close DESC\")"
  [{:keys [conn]} sql]
  (let [result (duckdb/sql->dataset conn sql)]
    (ds/rename-columns result
                       (into {} (map (juxt identity keyword))
                             (ds/column-names result)))))

(defn run!
  "Run a SQL statement ignoring the result. Useful for DDL or DML statements.

   Example:
   (run! db \"DROP TABLE IF EXISTS prices\")"
  [{:keys [conn]} sql]
  (duckdb/run-query! conn sql))
