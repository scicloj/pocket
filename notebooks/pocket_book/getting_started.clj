;; # Getting Started

;; [Pocket](https://github.com/scicloj/pocket) is a Clojure library for
;; filesystem-based caching of expensive computations. It persists results
;; to disk so they survive JVM restarts. Cache keys are derived from the
;; function identity and its arguments, so the same computation always
;; maps to the same cache entry.

;; ## Setup

(ns pocket-book.getting-started
  (:require
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]))

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

;; ## Important: use vars for functions
;;
;; Always pass functions as **vars** (`#'fn-name`), not as bare function
;; objects. Vars have stable names that produce consistent cache keys
;; across sessions. Pocket throws an error if you forget:
;;
;; ```clojure
;; ;; ✅ (pocket/cached #'my-function args)
;; ;; ❌ (pocket/cached my-function args)
;; ```
;;
;; See the [Usage Practices](pocket_book.usage_practices.html) chapter
;; for a detailed explanation and more best practices.

;; ## Next steps
;;
;; - [Configuration](pocket_book.configuration.html) — cache directory,
;;   in-memory eviction policies, `pocket.edn`
;; - [Recursive Caching in Pipelines](pocket_book.recursive_caching_in_pipelines.html) —
;;   chaining cached computations
;; - [Usage Practices](pocket_book.usage_practices.html) — invalidation
;;   strategies, testing, serialization, and more

;; ## Cleanup

(pocket/cleanup!)
