# Changelog

All notable changes to Pocket will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
- **Fix origin registry memory leak**: The origin registry no longer retains strong references to `Cached` objects or their arguments. Previously, `register-origin!` stored a pre-computed `->id` (which materialized large arguments into a persistent list) and a strong reference to the `Cached` object. Both now use `WeakReference`, and the origin id is computed lazily on lookup. This prevents unbounded heap growth when caching functions with large arguments (e.g., datasets).

## [0.2.2] - 2026-02-11
- bugfix: added default config under resources

## [0.2.1] - 2026-02-09
- **Origin-story through derefed values**: The origin registry now stores the `Cached` object alongside the origin identity. This enables `origin-story`, `origin-story-graph`, and `origin-story-mermaid` to follow derefed values back through the registry to their `Cached` origin — producing the full provenance DAG even when real (derefed) datasets flow through a pipeline.

## [0.2.0] - 2026-02-09
- **Origin registry**: Derefed `Cached` values now [carry their origin identity](https://scicloj.github.io/pocket/pocket_book.cache_keys.html#origin-registry-derefed-values-keep-their-identity). `->id` on a derefed value returns the same lightweight identity as `->id` on the `Cached` reference itself. This enables passing real (derefed) values to code that requires concrete types (e.g., `ml/evaluate-pipelines`) without losing provenance or cache key efficiency. The registry uses `WeakReference` and identity-based matching, so transformed values (new objects) fall back to content-based identity.
- **Docstrings**: Updated `->id` and `clear-mem-cache!` docstrings to document origin registry behavior.

## [0.1.2] - 2026-02-08
- **Dataset identity**: `PIdentifiable` now derives dataset identity from actual column data and metadata, not `toString` (which truncates rows beyond ~25 and could produce collisions for datasets differing only in elided rows)


## [0.1.1] - 2026-02-07
- `cached` and `caching-fn` now accept **keywords** in addition to vars (e.g., `(cached :train split-c)`)
- `origin-story-mermaid` preserves `:` prefix for keyword function nodes
- `origin-story-mermaid` wraps map values at commas (`<br>`) for readable multi-line boxes

## [0.1.0] - 2026-02-06

Initial release focusing on filesystem-based caching for data science pipelines.
