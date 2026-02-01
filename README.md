# Pocket

Filesystem-based [caching](https://en.wikipedia.org/wiki/Cache_(computing)) for expensive Clojure computations

## What is this about?

Pocket provides [content-addressable](https://en.wikipedia.org/wiki/Content-addressable_storage) caching with automatic [serialization](https://en.wikipedia.org/wiki/Serialization), making it easy to cache expensive function calls to disk and reuse results across sessions. It is designed for data science workflows with expensive intermediate steps that need to survive JVM restarts.

Pocket uses a two-layer caching architecture — durable disk storage via [Nippy](https://github.com/taoensso/nippy) and an in-memory cache backed by [core.cache](https://github.com/clojure/core.cache) (supporting [LRU](https://en.wikipedia.org/wiki/Cache_replacement_policies#LRU), [FIFO](https://en.wikipedia.org/wiki/Cache_replacement_policies#FIFO), [TTL](https://en.wikipedia.org/wiki/Time_to_live), and other [eviction policies](https://en.wikipedia.org/wiki/Cache_replacement_policies)) — with [thread-safe](https://en.wikipedia.org/wiki/Thread_safety) coordination so concurrent derefs compute only once.

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

Copyright © 2025 Scicloj

Distributed under the MIT License — see [LICENSE](LICENSE).
