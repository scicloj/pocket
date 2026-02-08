
;; # Under the hood: cache keys
;;
;; When Pocket caches a function call, it builds a **cache key** from
;; the function identity and all arguments. This chapter looks at how
;; that works internally and what it costs — especially for large
;; arguments like datasets.

;; ## Setup

(ns pocket-book.cache-keys
  (:require
   [scicloj.pocket :as pocket]
   [scicloj.pocket.impl.cache :as cache]
   [scicloj.pocket.protocols :as proto]
   [scicloj.kindly.v4.kind :as kind]
   [tablecloth.api :as tc]
   [tech.v3.dataset.modelling :as ds-mod]))

;; ## The four steps
;;
;; Every call to `pocket/cached` goes through four steps to produce a
;; filesystem path for the cache entry:
;;
;; 1. **`->id`** — convert each argument to its identity
;;    representation via the `PIdentifiable` protocol. Vars become
;;    fully-qualified symbols; `Cached` references become lightweight
;;    references; datasets become their full column data + metadata.
;;
;; 2. **`canonical-id`** — deep-sort maps and normalize the structure
;;    so that `{:a 1 :b 2}` and `{:b 2 :a 1}` produce the same key.
;;
;; 3. **`str`** — serialize the canonical form to a string.
;;
;; 4. **`sha`** — SHA-1 hash for a fixed-length, filesystem-safe path.
;;
;; When a function's arguments are small (scalars, keywords, or
;; `Cached` references), all four steps are sub-millisecond. But when a raw
;; dataset is passed directly, the full dataset content becomes part
;; of the cache key.

;; ## Measuring the cost
;;
;; Let's pass a 50,000-row dataset as a direct argument and time
;; each step:

(let [ds (-> (tc/dataset {:x (vec (range 50000))
                          :y (vec (range 50000))
                          :z (repeatedly 50000 rand)})
             (ds-mod/set-inference-target :y))
      ;; Step 1: ->id
      t0 (System/nanoTime)
      id (proto/->id ds)
      t1 (System/nanoTime)
      ;; Step 2: canonical-id
      cid (cache/canonical-id id)
      t2 (System/nanoTime)
      ;; Step 3: str
      s (str cid)
      t3 (System/nanoTime)
      ;; Step 4: sha
      _ (cache/sha s)
      t4 (System/nanoTime)]
  {:rows 50000
   :string-length (count s)
   :->id-ms (/ (- t1 t0) 1e6)
   :canonical-id-ms (/ (- t2 t1) 1e6)
   :str-ms (/ (- t3 t2) 1e6)
   :sha-ms (/ (- t4 t3) 1e6)})

;; The `str` serialization step dominates — it must walk the entire
;; nested structure and produce a ~1.5 MB string. SHA-1 hashing that
;; string is fast by comparison. Switching to a faster hash algorithm
;; would not meaningfully help.

;; ## Why `Cached` references matter
;;
;; When an argument is a `Cached` reference rather than a raw dataset, its
;; identity is a lightweight reference to the computation that produced
;; it — not the data itself. Compare:

(pocket/set-base-cache-dir! "/tmp/pocket-cache-keys")
(pocket/cleanup!)

(defn make-data [n]
  (tc/dataset {:x (vec (range n))
               :y (vec (range n))}))

;; Direct dataset — identity includes all 50,000 rows:
(let [ds (make-data 50000)
      t0 (System/nanoTime)
      _ (str (cache/canonical-id (proto/->id ds)))
      t1 (System/nanoTime)]
  {:direct-ms (/ (- t1 t0) 1e6)})

;; `Cached` reference — identity is just `(make-data 50000)`:
(let [data-c (pocket/cached #'make-data 50000)
      t0 (System/nanoTime)
      _ (str (cache/canonical-id (proto/->id data-c)))
      t1 (System/nanoTime)]
  {:cached-reference-ms (/ (- t1 t0) 1e6)})

;; The `Cached` reference is orders of magnitude faster because its identity
;; is a small form like `(pocket-book.cache-keys/make-data 50000)`,
;; regardless of how large the output dataset is.
;;
;; This is one of the key reasons to chain `pocket/cached` calls in a
;; pipeline: each step's cache key references its inputs by identity
;; rather than by content, keeping key generation fast and enabling
;; full provenance through the DAG.

;; ## Cleanup

(pocket/cleanup!)
