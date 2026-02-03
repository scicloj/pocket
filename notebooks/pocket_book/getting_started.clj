;; # Getting Started

(ns pocket-book.getting-started
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; [Pocket](https://github.com/scicloj/pocket) is a Clojure library for
;; filesystem-based caching of expensive computations. It persists results
;; to disk so they survive JVM restarts, and uses content-addressable
;; storage so cache keys are derived from what you compute, not where
;; you store it.

;; ## Setup

;; First, we set up a cache directory and define an expensive computation:

(def cache-dir "/tmp/pocket-demo")

(pocket/set-base-cache-dir! cache-dir)

;; Start fresh so the examples below run from a clean slate:
(pocket/cleanup!)

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

(type cached-result)

(kind/test-last [= scicloj.pocket.impl.cache.Cached])

;;; First deref (computes and caches):
(time @cached-result)

(kind/test-last [= 30])

;;; Second deref (loaded from cache, instant):
(time @cached-result)

;; ## Wrapping functions with `caching-fn`

;; For convenience, `caching-fn` wraps a function so that every call
;; returns a `Cached` object:

(def cached-expensive
  (pocket/caching-fn #'expensive-calculation))

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

(kind/test-last [nil?])

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
;;
;; Pocket will throw an error if you pass a bare function instead of a var,
;; so this mistake is caught immediately rather than producing unreachable
;; cache entries.

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
  ;; v3: switched from mean to median
  (:data data))

;;; Version 3 of the processing:
(deref (pocket/cached #'process-data
                      {:data [1 2 3]}
                      {:scale 2 :version 3}))

;; When the implementation changes again, simply bump to `:version 4`.
;; Previous cached results remain on disk (useful if you need to compare),
;; while the new version computes fresh results.

;; ## What's on disk?
;;
;; Pocket names cache directories after the actual Clojure call that
;; produced them — the function name and its arguments — so you can
;; tell at a glance what each entry represents:

(kind/code (pocket/dir-tree))

;; Some prefix directories may appear empty — those held entries
;; that were removed by the `invalidate!` and `invalidate-fn!`
;; calls above.

;; Each directory also contains a `meta.edn` file with metadata
;; about the cached computation:

(-> (pocket/cache-entries)
    first
    :path
    (str "/meta.edn")
    slurp
    clojure.edn/read-string)

;; This same information is available through the API, without
;; reading files directly:

(pocket/cache-entries)

;; And as aggregate statistics:

(pocket/cache-stats)

;; ## Long cache keys
;;
;; When a cache key string exceeds 240 characters, Pocket falls back to
;; using a SHA-1 hash as the directory name. This ensures the filesystem
;; can handle arbitrarily complex arguments while maintaining correct
;; caching behavior.

(defn process-long-text [text]
  (str "Processed: " (count text) " chars"))

(def long-text (apply str (repeat 300 "x")))

(deref (pocket/cached #'process-long-text long-text))

(kind/test-last [(fn [result] (clojure.string/starts-with? result "Processed:"))])

;; The entry is stored with a hash-based directory name:

(kind/code (pocket/dir-tree))

;; But the `meta.edn` file inside still contains the full details,
;; so `cache-entries` and `invalidate-fn!` work correctly:

(-> (pocket/cache-entries "pocket-book.getting-started/process-long-text")
    first
    :fn-name)

(kind/test-last [= "pocket-book.getting-started/process-long-text"])

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
