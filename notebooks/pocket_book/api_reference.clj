;; # API Reference

^{:kindly/hide-code true
  :kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns pocket-book.api-reference
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))


;; ## Setup

;; A few preparations for the code examples below:

(require '[scicloj.pocket :as pocket])

(def cache-dir "/tmp/pocket-demo-reference")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

(defn expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y))

;; ## Reference

(kind/doc #'pocket/*base-cache-dir*)

;; The current value:

pocket/*base-cache-dir*

(kind/doc #'pocket/set-base-cache-dir!)

(pocket/set-base-cache-dir! "/tmp/pocket-demo-2")
pocket/*base-cache-dir*

;; Restore it for the rest of the notebook:

(pocket/set-base-cache-dir! cache-dir)

(kind/doc #'pocket/config)

;; Inspect the current effective configuration:

(pocket/config)


(kind/doc #'pocket/cached)

;; `cached` returns a `Cached` object — the computation is not yet executed:

(def my-result (pocket/cached #'expensive-calculation 100 200))
(type my-result)

(kind/test-last [= scicloj.pocket.impl.cache.Cached])

;; The computation runs when we deref:

(deref my-result)

(kind/test-last [= 300])

;; Derefing again loads from cache (no recomputation):

(deref my-result)

(kind/test-last [= 300])

(kind/doc #'pocket/caching-fn)

;; `caching-fn` wraps a function so that every call returns a `Cached` object:

(def my-caching-fn (pocket/caching-fn #'expensive-calculation))

(deref (my-caching-fn 3 4))

;; Same args hit the cache:

(deref (my-caching-fn 3 4))

;; `caching-fn` accepts an optional map to override per-function configuration:
;;
;; ```clojure
;; (pocket/caching-fn #'f {:storage :mem})     ;; in-memory only
;; (pocket/caching-fn #'f {:storage :none})    ;; identity tracking only
;; (pocket/caching-fn #'f {:cache-dir "/tmp/alt"})  ;; alternate cache dir
;; ```
;;
;; See the [Configuration chapter](pocket_book.configuration.html#storage-policies)
;; for details on storage modes and the full option map.

(kind/doc #'pocket/maybe-deref)

;; A plain value passes through unchanged:

(pocket/maybe-deref 42)

(kind/test-last [= 42])

;; A `Cached` value gets derefed:

(pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200))

(kind/test-last [= 300])

(kind/doc #'pocket/->id)

;; A var's identity is its fully-qualified name:

(pocket/->id #'expensive-calculation)

(kind/test-last [(fn [id] (= (name id) "expensive-calculation"))])

;; A map's identity is itself (maps are deep-sorted later for stable cache paths):

(pocket/->id {:b 2 :a 1})

;; A `Cached` object's identity captures the full computation —
;; function name and argument identities — without running it:

(pocket/->id (pocket/cached #'expensive-calculation 100 200))

;; `nil` is handled as well:

(pocket/->id nil)

(kind/test-last [nil?])

(kind/doc #'pocket/set-mem-cache-options!)

;; Switch to a FIFO policy with 100 entries:

(pocket/set-mem-cache-options! {:policy :fifo :threshold 100})

;; Reset to default:

(pocket/set-mem-cache-options! {:policy :lru :threshold 256})

(kind/doc #'pocket/reset-mem-cache-options!)

;; Reset mem-cache configuration to library defaults:

(pocket/reset-mem-cache-options!)

(kind/doc #'pocket/*storage*)

(kind/doc #'pocket/set-storage!)

;; Switch to memory-only storage:

(pocket/set-storage! :mem)

;; Reset to default:

(pocket/set-storage! nil)

(kind/test-last [(fn [_] (= :mem+disk (:storage (pocket/config))))])

(kind/doc #'pocket/cleanup!)

(pocket/cleanup!)

(kind/doc #'pocket/clear-mem-cache!)

;; Clear in-memory cache without touching disk:

(deref (pocket/cached #'expensive-calculation 10 20))

(pocket/clear-mem-cache!)

(kind/doc #'pocket/invalidate!)

;; Remove a specific cached entry:

(deref (pocket/cached #'expensive-calculation 10 20))

(pocket/invalidate! #'expensive-calculation 10 20)

;; Derefing again will recompute:

(deref (pocket/cached #'expensive-calculation 10 20))

(kind/doc #'pocket/invalidate-fn!)

;; Cache a few entries, then invalidate them all:

(deref (pocket/cached #'expensive-calculation 1 2))
(deref (pocket/cached #'expensive-calculation 3 4))

(pocket/invalidate-fn! #'expensive-calculation)

(pocket/cleanup!)

(kind/doc #'pocket/cache-entries)

;; List all cached entries:

(deref (pocket/cached #'expensive-calculation 10 20))
(deref (pocket/cached #'expensive-calculation 3 4))

(pocket/cache-entries)

(kind/test-last [(fn [entries] (= 2 (count entries)))])

;; Filter by function name:

(pocket/cache-entries "pocket-book.api-reference/expensive-calculation")

(kind/doc #'pocket/cache-stats)

(pocket/cache-stats)

(pocket/cleanup!)
;; ## Extending `PIdentifiable`
;;
;; You can customize how your types contribute to cache keys by
;; extending the `PIdentifiable` protocol. See the
;; [Extending Pocket](pocket_book.extending_pocket.html) chapter
;; for a full walkthrough with examples.
