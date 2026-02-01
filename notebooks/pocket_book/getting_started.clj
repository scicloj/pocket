;; # Getting Started

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns pocket-book.getting-started
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Setup

;; First, we set up a cache directory and define an expensive computation:

(def cache-dir "/tmp/pocket-demo")

(pocket/set-base-cache-dir! cache-dir)

(defn expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y))

;; ## Background: deref in Clojure
;;
;; In Clojure, [`deref`](https://clojure.org/reference/concurrency#deref)
;; extracts a value from a reference type. It can be written as `(deref x)`
;; or with the shorthand reader macro `@x` — both are equivalent.
;; Pocket's `cached` returns a `Cached` object that implements `IDeref`,
;; so you use `@` (or `deref`) to trigger the computation and retrieve
;; the result.

;; ## Creating a cached computation

;; `cached` creates a [lazy](https://en.wikipedia.org/wiki/Lazy_evaluation) cached computation.
;; It returns a `Cached` object — the computation won't run until we deref it:

(def cached-result
  (pocket/cached #'expensive-calculation 10 20))

cached-result

;;; First deref (computes and caches):
(time @cached-result)

;;; Second deref (loaded from cache, instant):
(time @cached-result)

;; ## Wrapping functions with `cached-fn`

;; For convenience, `cached-fn` wraps a function so that every call
;; returns a `Cached` object:

(def cached-expensive
  (pocket/cached-fn #'expensive-calculation))

;;; First call:
(time @(cached-expensive 5 15))

;;; Same args — cache hit:
(time @(cached-expensive 5 15))

;;; Different args — new computation:
(time @(cached-expensive 7 8))

;; ## Nil handling

;; Pocket properly handles `nil` values. Since the cache uses files on disk,
;; it needs to distinguish "never computed" from "computed and got `nil`".
;; It does this with a special marker file:

(defn returns-nil [] nil)

(def nil-result (pocket/cached #'returns-nil))

;;; Cached nil value:
(deref nil-result)

;;; Loading nil from cache:
(deref nil-result)

;; ## Usage notes

;; ### Use [vars](https://clojure.org/reference/vars) for functions
;;
;; Always use `#'function-name` (var), not `function-name` (function object).
;; Vars have stable names that produce consistent cache keys across sessions.
;; Function objects have unstable identity and would create a new cache entry
;; every time the code is reloaded.
;;
;; ```clojure
;; ;; ✅ Good — stable cache key from var name
;; (pocket/cached #'my-function args)
;;
;; ;; ❌ Bad — unstable identity, defeats caching
;; (pocket/cached my-function args)
;; ```

;; ### [Cache invalidation](https://en.wikipedia.org/wiki/Cache_invalidation)
;;
;; Pocket does **not** detect function implementation changes. If you modify
;; a function's body, the cache key remains the same (it's based on the
;; function name and arguments, not the implementation).
;;
;; You can invalidate cached entries at different levels of granularity:
;;
;; - `invalidate!` — remove a specific entry (by var + args)
;; - `invalidate-fn!` — remove all entries for a given function
;; - `cleanup!` — remove everything

;; For example, to invalidate a single cached result:

(deref (pocket/cached #'expensive-calculation 10 20))

(pocket/invalidate! #'expensive-calculation 10 20)

;; Or invalidate all cached results for a function:

(deref (pocket/cached #'expensive-calculation 1 2))
(deref (pocket/cached #'expensive-calculation 3 4))

(pocket/invalidate-fn! #'expensive-calculation)

;; ### Versioning function inputs
;;
;; Since Pocket derives cache keys from function name and arguments (not the
;; function body), changing a function's implementation won't automatically
;; produce a new cache entry. Rather than invalidating the cache, a practical
;; approach is to add a version key to the function's input map. When you
;; change the implementation, bump the version — Pocket will treat it as
;; a new computation with a distinct cache key:

(defn process-data [data opts]
  (let [data (pocket/maybe-deref data)]
    ;; v3: switched from mean to median
    (:data data)))

;;; Version 3 of the processing:
(deref (pocket/cached #'process-data
                      {:data [1 2 3]}
                      {:scale 2 :version 3}))

;; When the implementation changes again, simply bump to `:version 4`.
;; Previous cached results remain on disk (useful if you need to compare),
;; while the new version computes fresh results.

;; ## Cleanup

(pocket/cleanup!)

;; ## Serialization
;;
;; Pocket uses [Nippy](https://github.com/taoensso/nippy) for serialization.
;; Most Clojure data types are supported, but you cannot cache values that
;; aren't serializable — such as open file handles, network connections,
;; or stateful Java objects.

;; ## When to use Pocket
;;
;; | Feature | Pocket | `clojure.core/memoize` | `core.memoize` |
;; |---------|--------|------------------------|----------------|
;; | Persistence | Disk + memory | Memory only | Memory only |
;; | Cross-session | Yes | No | No |
;; | Content-addressable | Yes | No | No |
;; | Lazy evaluation | `IDeref` | Eager | Eager |
;;
;; Use Pocket when computations take minutes or hours, results need to
;; survive JVM restarts, or you're building data science pipelines with
;; expensive intermediate steps.
;;
;; Use `memoize` or `core.memoize` for fast, in-memory, single-session caching.
