
;; # Under the hood: cache keys
;;
;; When Pocket caches a function call, it builds a **cache key** from
;; the function identity and all arguments. This chapter looks at how
;; that works internally and what it costs — especially for large
;; arguments like datasets.

;; ## Setup

(ns pocket-book.cache-keys
  (:require
   ;; Pocket API and internals:
   [scicloj.pocket :as pocket]
   [scicloj.pocket.impl.cache :as cache]
   [scicloj.pocket.protocols :as proto]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Data processing:
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

;; ## Origin registry: derefed values keep their identity
;;
;; Sometimes we need to pass real values — not `Cached` references — to
;; code that requires concrete types. For example, metamorph.ml's
;; `evaluate-pipelines` checks `(instance? Dataset ds)`, which fails
;; for `Cached` references. The natural solution is to deref the
;; reference first, but that would lose the lightweight identity:
;; the derefed dataset would need full content hashing for its cache key.
;;
;; Pocket solves this with an **origin registry**. When a `Cached` value
;; is derefed, the result is registered in a side channel that maps it
;; back to the `Cached` identity. Later, when `->id` is called on that
;; derefed value, the registry provides the lightweight identity instead
;; of hashing the content.

;; A derefed value has the same identity as its `Cached` reference:

(let [data-c (pocket/cached #'make-data 50000)
      data (deref data-c)]
  (= (proto/->id data) (proto/->id data-c)))

(kind/test-last [true?])

;; And the performance is the same — sub-millisecond, like a `Cached`
;; reference, rather than the tens-of-milliseconds cost of hashing
;; 50,000 rows:

(let [data-c (pocket/cached #'make-data 50000)
      data (deref data-c)
      t0 (System/nanoTime)
      _ (str (cache/canonical-id (proto/->id data)))
      t1 (System/nanoTime)]
  {:derefed-with-origin-ms (/ (- t1 t0) 1e6)})

;; ### What breaks the link
;;
;; Transforming a derefed value creates a **new object**. The new
;; object is not in the registry, so `->id` falls back to
;; content-based identity. This is intentional — a transformed
;; dataset is semantically different from its source, and its
;; cache key should reflect its actual content.

(let [data-c (pocket/cached #'make-data 100)
      data (deref data-c)
      transformed (tc/add-column data :z (repeat 100 0))]
  {:original-has-origin (= (proto/->id data) (proto/->id data-c))
   :transformed-has-origin (= (proto/->id transformed) (proto/->id data-c))})

(kind/test-last
 [(fn [{:keys [original-has-origin transformed-has-origin]}]
    (and original-has-origin (not transformed-has-origin)))])

;; ### Which values are registered
;;
;; Only values implementing `clojure.lang.IObj` — maps, vectors,
;; sets, and datasets — are registered. The JVM
;; [interns](https://en.wikipedia.org/wiki/String_interning) small
;; integers and other primitives, meaning `(Long/valueOf 1)` always
;; returns the same object. Registering such values would cause
;; false origin matches across unrelated computations. Excluding
;; them avoids this problem entirely.

;; ## Cleanup

(pocket/cleanup!)
