# Changelog

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
