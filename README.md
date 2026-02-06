# Pocket

Filesystem-based [caching](https://en.wikipedia.org/wiki/Cache_(computing)) for expensive Clojure computations

## What is this about?

Pocket makes it easy to cache expensive function calls to disk and reuse results across sessions. If a computation takes longer than disk I/O, Pocket can help — wrap it once, and the result is saved for next time. This is especially useful for data science workflows with expensive intermediate steps that need to survive JVM restarts.

Under the hood, Pocket derives cache keys from the function identity and its arguments (so the same computation always maps to the same cache entry), uses [Nippy](https://github.com/taoensso/nippy) for fast serialization, and provides an in-memory layer backed by [core.cache](https://github.com/clojure/core.cache) with configurable [eviction policies](https://en.wikipedia.org/wiki/Cache_replacement_policies). Concurrent uses of the same computation are [thread-safe](https://en.wikipedia.org/wiki/Thread_safety) — the computation runs only once.

## Key features

- **Lazy evaluation** — `cached` returns a deref-able value; computation runs only when needed
- **Pipeline caching** — chain cached steps into pipelines with automatic provenance tracking
- **Storage policies** — `:mem+disk` (default), `:mem` (no disk I/O), or `:none` (identity tracking only)
- **DAG introspection** — reconstruct the full computation graph and render it as a flowchart
- **Thread-safe** — concurrent uses of the same computation run it exactly once
- **Configurable** — cache directory, eviction policy, and storage mode via `pocket.edn`, environment variables, or code

## General info
|||
|-|-|
|Website | [https://scicloj.github.io/pocket/](https://scicloj.github.io/pocket/)
|Source |[![(GitHub repo)](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)](https://github.com/scicloj/pocket)|
|Deps |[![Clojars Project](https://img.shields.io/clojars/v/org.scicloj/pocket.svg)](https://clojars.org/org.scicloj/pocket)|
|License |[MIT](https://github.com/scicloj/pocket/blob/master/LICENSE)|
|Status |🛠alpha🛠|

## Quick example

```clojure
(require '[scicloj.pocket :as pocket])

(pocket/set-base-cache-dir! "/tmp/my-cache")

(defn expensive-calculation [x y]
  (Thread/sleep 5000)
  (+ x y))

;; Create a lazy cached computation
(def result (pocket/cached #'expensive-calculation 10 20))

@result  ;; => 30 (computes and caches — takes 5 seconds)
@result  ;; => 30 (loads from cache — instant)
```

## Discussion

The best places to discuss this project are:
* a topic thread under the [#data-science channel](https://clojurians.zulipchat.com/#narrow/channel/151924-data-science/) at the Clojurians Zulip (more about chat channels [here](https://scicloj.github.io/docs/community/chat/)) 
  * It is highly recommended to create separate [topics](https://zulip.com/help/introduction-to-topics) for separate questions.
* a [github issue](https://github.com/scicloj/pocket/issues)

## License

Copyright © 2025-2026 Scicloj

Distributed under the MIT License — see [LICENSE](LICENSE).
