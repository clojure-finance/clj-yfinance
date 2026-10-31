# Changelog

## 0.1.10 — 2026-10-31

### Added

- `clj-yfinance.experimental.timeseries`: full income statement, balance sheet
  and cash flow statement from Yahoo's fundamentals-timeseries endpoint (no
  cookie or crumb). `fetch-statements` / `fetch-statements*` return one row per
  statement, field, frequency (annual, quarterly, trailing) and period end, with
  the reporting currency; `fetch-timeseries` / `fetch-timeseries*` give raw
  access by Yahoo type string, sent in chunks of at most 100 types per request
  with the chart endpoint's retry and host failover. The field catalog
  (`statement-fields`, 375 fields) comes from python-yfinance's
  `fundamentals_keys`; `field->keyword` maps Yahoo names to kebab-case keywords.
- `clj-yfinance.experimental.earnings`: `fetch-earnings-history` /
  `fetch-earnings-history*` (last four quarters from the quoteSummary
  `earnings` module: period end, report date and instant, EPS actual vs.
  estimate, surprise) and `fetch-earnings-dates` / `fetch-earnings-dates*`
  (announcement history from Yahoo's visualization API, paged at 250 rows per
  request).
- `fetch-historical*` returns the chart metadata (symbol, currency, exchange,
  instrument type, first trade date, time zone, GMT offset, names) as `:meta`.
- `fetch-historical*` option `:events` (default `false`): also return the
  window's dividends and splits as `:events`, in the shape
  `fetch-dividends-splits` returns, from the same request.
- `experimental.auth/authenticated-request` accepts `:method` (`:get` or
  `:post`), `:body` and `:content-type`; the 401 retry repeats the same request.

### Changed

- `clj-yfinance.http/get-with-failover*` is the generic GET with retry, backoff
  and host failover that the chart endpoint uses; chart behaviour is unchanged.
- `fetch-financials` docs point to `experimental.timeseries` for full
  statements.

## 0.1.9 — 2026-10-20

### Fixed

- `clj-yfinance.dataset/dividends-splits->dataset` (and the Kindly variant)
  threw on real Yahoo data: event maps are keyed by keywordized epoch strings
  (`:1699574400`, not always equal to the event date), which were used as the
  timestamp. Timestamps now come from
  each event's `:date`, and rows are sorted by date. Split
  `:numerator`/`:denominator` columns are now `float64`, matching Yahoo's
  values.
- Experimental quoteSummary functions (`fetch-fundamentals`, `fetch-financials`,
  …) retried a 401 with the stale crumb still in the URL, so the retry could
  not succeed. `auth/authenticated-request` now also accepts the URL as a
  function of the crumb and rebuilds it after the refresh; options use the same
  path.
- `clj-yfinance.kindly` functions returned `[nil]` instead of `nil` when there
  was no data.
- `auth/session-info` `:age-minutes` is a double instead of a ratio; the
  crumb value is no longer printed.
- `clj-yfinance.duckdb/run!` no longer triggers a "replacing clojure.core/run!"
  warning on load.
- Demo notebook (`examples/finance_demo.clj`) runs end to end again: chart
  titles go through `plotly/base` (current tableplot's `plot` takes one
  argument), and the normalised-performance chart and ticker-info table no
  longer throw.

## 0.1.8 — 2026-08-22

### Breaking

- `:auto-adjust` now defaults to `true` in `fetch-historical` / `fetch-historical*`
  (and everything built on them: datasets, Parquet, DuckDB). `:open`/`:high`/
  `:low`/`:close` are back-adjusted for dividends and splits, matching
  python-yfinance. Pass `:auto-adjust false` for raw OHLC.
- `:adjusted` option removed from `fetch-historical` / `fetch-historical*` (and
  the pass-through in `parquet` / `duckdb`). Yahoo's adjclose series is always
  requested and `:adj-close` is always present when Yahoo provides it. Rows
  therefore have a fixed shape; under `:auto-adjust true`, `:adj-close` equals
  `:close`.
- License changed from EPL-2.0 to Apache-2.0.
- `project.clj` removed; `deps.edn` + `build.clj` are the only build files.

### Changed

- OHLC row building extracted into the pure `clj-yfinance.parse/chart->rows`
  (with unit tests).
- Provided-scope POM dependencies are derived from the `deps.edn` aliases.
- tech.ml.dataset alias bumped to 8.024.
- CI now runs the dataset tests and fails the build on test failures (previously
  every step exited 0 regardless of results).
