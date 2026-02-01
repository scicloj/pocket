;; # API Reference

^{:kindly/options {:kinds-that-hide-code #{:kind/doc}}}
(ns pocket-book.reference
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Setup

(def cache-dir "/tmp/pocket-demo-reference")

(pocket/set-base-cache-dir! cache-dir)

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

(kind/doc #'pocket/cached)

;; `cached` returns a `Cached` object — the computation is not yet executed:

(def my-result (pocket/cached #'expensive-calculation 100 200))
my-result

;; The computation runs when we deref:

(deref my-result)

;; Derefing again loads from cache (no recomputation):

(deref my-result)

(kind/doc #'pocket/cached-fn)

;; `cached-fn` wraps a function so that every call returns a `Cached` object:

(def my-cached-fn (pocket/cached-fn #'expensive-calculation))

(deref (my-cached-fn 3 4))

;; Same args hit the cache:

(deref (my-cached-fn 3 4))

(kind/doc #'pocket/maybe-deref)

;; A plain value passes through unchanged:

(pocket/maybe-deref 42)

;; A `Cached` value gets derefed:

(pocket/maybe-deref (pocket/cached #'expensive-calculation 100 200))

(kind/doc #'pocket/->id)

;; A var's identity is its name:

(pocket/->id #'expensive-calculation)

;; A map's identity is itself (with keys sorted for stability):

(pocket/->id {:b 2 :a 1})

;; A `Cached` object's identity captures the full computation —
;; function name and argument identities — without running it:

(pocket/->id (pocket/cached #'expensive-calculation 100 200))

;; `nil` is handled as well:

(pocket/->id nil)

(kind/doc #'pocket/set-mem-cache-options!)

;; Switch to a FIFO policy with 100 entries:

(pocket/set-mem-cache-options! {:policy :fifo :threshold 100})

;; Reset to default:

(pocket/set-mem-cache-options! {:policy :lru :threshold 256})

(kind/doc #'pocket/cleanup!)

(pocket/cleanup!)
