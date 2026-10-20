# Changelog

## Unreleased

### Fixed

- `clj-yfinance.dataset/dividends-splits->dataset` (and the Kindly variant)
  threw on real Yahoo data: event maps are keyed by keywordized epoch strings
  (`:1699574400`), which were used as the timestamp. Timestamps now come from
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
