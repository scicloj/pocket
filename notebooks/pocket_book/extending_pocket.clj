;; # Extending Pocket

(ns pocket-book.extending-pocket
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

;; ## Setup

;; Configure logging to stdout at debug level, so we can see
;; Pocket's cache hit/miss/write messages:

(do (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "debug")
    (System/setProperty "org.slf4j.simpleLogger.logFile" "System.out"))

(def cache-dir "/tmp/pocket-extending")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## The `PIdentifiable` protocol
;;
;; Pocket derives cache keys from the **identity** of the function
;; and its arguments. The `PIdentifiable` protocol controls how
;; each value contributes to the cache key:

(kind/doc #'pocket/->id)

;; ## Default behaviors
;;
;; Pocket provides default implementations for common types:

;; A var's identity is its unqualified name:

(pocket/->id #'clojure.core/map)

;; A map's identity is itself (keys are sorted for stability):

(pocket/->id {:b 2 :a 1})

;; A `Cached` object's identity captures the full computation graph:

(defn add [x y] (+ x y))

(pocket/->id (pocket/cached #'add 1 2))

;; `nil` is handled:

(pocket/->id nil)

;; ## Extending for custom types
;;
;; If you have domain-specific types, you can control how they
;; appear in cache keys by extending `PIdentifiable`. This is
;; useful when the default behavior (which uses the object itself)
;; doesn't produce stable or meaningful cache keys.
;;
;; For example, suppose you have a record representing a dataset
;; reference:

(defrecord DatasetRef [source version])

;; Without extending the protocol, a `DatasetRef` would use its
;; default `toString` representation as its identity — which works
;; but isn't very readable in cache directory names.
;;
;; Let's give it a concise, meaningful identity:

(extend-protocol pocket/PIdentifiable
  DatasetRef
  (->id [this]
    (symbol (str (:source this) "-v" (:version this)))))

;; Now the identity is a clean symbol:

(pocket/->id (->DatasetRef "census" 3))

;; ## Using custom types in cached computations

(defn analyze-dataset
  "Simulate analyzing a dataset."
  [dataset-ref opts]
  (println "  Analyzing" (:source dataset-ref) "v" (:version dataset-ref) "...")
  (Thread/sleep 200)
  {:source (:source dataset-ref)
   :version (:version dataset-ref)
   :rows 1000
   :method (:method opts)})

;; The cache key now includes our custom identity:

(def analysis
  (pocket/cached #'analyze-dataset
                 (->DatasetRef "census" 3)
                 {:method :regression}))

(pocket/->id analysis)

;; First deref computes:

(deref analysis)

;; Second deref loads from cache:

(deref analysis)

;; A different version creates a different cache entry:

(deref (pocket/cached #'analyze-dataset
                      (->DatasetRef "census" 4)
                      {:method :regression}))

;; ## What's on disk?
;;
;; The cache directory names reflect our custom identities:

(kind/code (pocket/dir-tree))

;; ## Guidelines
;;
;; When extending `PIdentifiable`:
;;
;; - **Return stable values.** The identity must be the same across
;;   JVM sessions for the same logical input. Avoid including
;;   timestamps, random values, or object addresses.
;;
;; - **Return distinct values.** Two logically different inputs must
;;   produce different identities. If they don't, Pocket will treat
;;   them as the same computation and return stale results.
;;
;; - **Keep it readable.** The identity becomes part of the cache
;;   directory name. Symbols and short strings work well.
;;
;; - **Prefer symbols or keywords** over complex nested structures.
;;   They produce clean, short directory names.
;;
;; - **Records and plain maps can collide.** A record like
;;   `(->DatasetRef "census" 3)` and a plain map
;;   `{:source "census" :version 3}` produce the same default cache
;;   key (both are maps with the same keys). If you use records as
;;   cache arguments, extend `PIdentifiable` to give them a distinct
;;   identity — as shown above.

;; ## Cleanup

(pocket/cleanup!)
