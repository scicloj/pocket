# Pocket

Filesystem-based [caching](https://en.wikipedia.org/wiki/Cache_(computing)) for expensive Clojure computations

## What is this about?

Pocket makes it easy to cache expensive function calls to disk and reuse results across sessions. If a computation takes longer than disk I/O, Pocket can help — wrap it once, and the result is saved for next time. This is especially useful for data science workflows with expensive intermediate steps that need to survive JVM restarts.

Under the hood, Pocket uses [content-addressable storage](https://en.wikipedia.org/wiki/Content-addressable_storage) (cache keys are derived from what you compute, not where you store it), [Nippy](https://github.com/taoensso/nippy) for fast serialization, and an in-memory layer backed by [core.cache](https://github.com/clojure/core.cache) with configurable [eviction policies](https://en.wikipedia.org/wiki/Cache_replacement_policies). Concurrent derefs of the same computation are [thread-safe](https://en.wikipedia.org/wiki/Thread_safety) — the computation runs only once.

## General info
|||
|-|-|
|Website | [https://scicloj.github.io/pocket/](https://scicloj.github.io/pocket/)
|Source |[![(GitHub repo)](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)](https://github.com/scicloj/pocket)|
|Deps |[![Clojars Project](https://img.shields.io/clojars/v/io.github.scicloj/pocket.svg)](https://clojars.org/io.github.scicloj/pocket)|
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

## License

Copyright © 2025-2026 Scicloj

Distributed under the MIT License — see [LICENSE](LICENSE).
