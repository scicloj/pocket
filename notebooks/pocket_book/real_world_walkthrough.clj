;; # Real-World Walkthrough: Weather Analysis Pipeline
;;
;; **Last modified: 2026-02-08**

;; ## Overview

;; This walkthrough demonstrates a realistic data pipeline with
;; multiple stages and branching dependencies. We'll simulate
;; a weather analysis workflow where raw sensor readings are
;; cleaned, then fed into two independent analyses — one for
;; temperature trends and one for rainfall totals. Both analyses
;; share the same cleaned data, so Pocket caches it once and
;; reuses it.
;;
;; This builds on the concepts from
;; [Recursive Caching in Pipelines](pocket_book.recursive_caching_in_pipelines.html),
;; adding branching dependencies where two analyses share a common
;; upstream computation.
;;
;; The dependency graph: `fetch-readings` → `clean-data` →
;; both `temperature-trends` and `rainfall-totals` → `summary`.

^{:kindly/hide-code true
  :kindly/kind :kind/mermaid}
["flowchart TD
    FR[fetch-readings] --> CD[clean-data]
    CD --> TT[temperature-trends]
    CD --> RT[rainfall-totals]
    TT --> S[summary]
    RT --> S"]

;; ## Setup

(ns pocket-book.real-world-walkthrough
  (:require
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Filesystem utilities:
   [babashka.fs :as fs]))

(def cache-dir "/tmp/pocket-walkthrough")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## Pipeline functions

;; Each function simulates real work with a short delay.
;; Note that these are plain Clojure functions — they know
;; nothing about caching.

(defn fetch-readings
  "Simulate fetching raw sensor data for a city."
  [opts]
  (println "  Fetching readings for" (:city opts) "...")
  (Thread/sleep 300)
  {:city (:city opts)
   :source (:source opts)
   :readings (case (:city opts)
               "Paris"
               [{:day 1 :temp-c 15.0 :rain-mm 2.1}
                {:day 2 :temp-c 17.3 :rain-mm 0.0}
                {:day 3 :temp-c 14.6 :rain-mm 7.8}
                {:day 4 :temp-c 18.1 :rain-mm 0.0}
                {:day 5 :temp-c 13.9 :rain-mm 4.5}
                {:day 6 :temp-c 16.7 :rain-mm 0.0}
                {:day 7 :temp-c 19.2 :rain-mm 1.0}]
               ;; default (London, etc.)
               [{:day 1 :temp-c 18.2 :rain-mm 0.0}
                {:day 2 :temp-c 21.5 :rain-mm 5.2}
                {:day 3 :temp-c 19.8 :rain-mm 12.1}
                {:day 4 :temp-c 22.0 :rain-mm 0.0}
                {:day 5 :temp-c 16.3 :rain-mm 8.4}
                {:day 6 :temp-c 20.1 :rain-mm 0.0}
                {:day 7 :temp-c 23.7 :rain-mm 3.3}])})

(defn clean-data
  "Remove readings with missing values and round numbers."
  [raw-data opts]
  (println "  Cleaning data for" (:city raw-data) "with" opts "...")
  (Thread/sleep 200)
  (let [precision (:precision opts 10)]
    (update raw-data :readings
            (fn [rs]
              (->> rs
                   (filter #(and (:temp-c %) (:rain-mm %)))
                   (mapv #(-> %
                              (update :temp-c (fn [t] (Math/round (* t (double precision)))))
                              (update :rain-mm (fn [r] (Math/round (* r (double precision))))))))))))

(defn temperature-trends
  "Compute temperature statistics from cleaned data."
  [clean-data opts]
  (println "  Analyzing temperature for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let [temps (map :temp-c (:readings clean-data))
        n (count temps)]
    {:city (:city clean-data)
     :unit (:unit opts)
     :min-temp (apply min temps)
     :max-temp (apply max temps)
     :mean-temp (quot (reduce + temps) n)
     :days n}))

(defn rainfall-totals
  "Compute rainfall statistics from cleaned data."
  [clean-data opts]
  (println "  Analyzing rainfall for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let [rains (map :rain-mm (:readings clean-data))
        rainy-days (count (filter pos? rains))]
    {:city (:city clean-data)
     :unit (:unit opts)
     :total-rain (reduce + rains)
     :rainy-days rainy-days
     :dry-days (- (count rains) rainy-days)}))

(defn summary
  "Combine temperature and rainfall analyses into a report."
  [temp-analysis rain-analysis]
  (println "  Generating summary for" (:city temp-analysis) "...")
  (Thread/sleep 200)
  (merge temp-analysis rain-analysis
         {:report (str (:city temp-analysis)
                       ": temp range "
                       (:min-temp temp-analysis) "–"
                       (:max-temp temp-analysis)
                       ", total rain "
                       (:total-rain rain-analysis)
                       " over " (:rainy-days rain-analysis) " days")}))

;; ## First run: everything computes

;; We build the pipeline using `cached`. Each call returns
;; a `Cached` object that we pass directly to the next stage.
;; Pocket derives cache keys from the computation graph, not
;; from intermediate values.

(println "=== First run ===")

(let [raw (pocket/cached #'fetch-readings {:city "London" :days 7 :source :api})
      clean (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

(kind/test-last [(fn [result] (every? result [:city :report :min-temp :max-temp :total-rain :rainy-days]))])

;; Every function ran. Notice the log messages showing cache misses,
;; computations, and writes.

;; ## Second run: everything from cache

;; Running the exact same pipeline again — nothing recomputes:

(println "\n=== Second run (fully cached) ===")

(let [raw (pocket/cached #'fetch-readings {:city "London" :days 7 :source :api})
      clean (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

(kind/test-last [(fn [result] (= "London" (:city result)))])

;; No log output — served entirely from the in-memory cache.

;; ## Changing an upstream input

;; Now suppose we want a different city. The entire pipeline
;; recomputes because the root input changed:

(println "\n=== Different city ===")

(let [raw (pocket/cached #'fetch-readings {:city "Paris" :days 7 :source :api})
      clean (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

(kind/test-last [(fn [result] (= "Paris" (:city result)))])

;; But running it again is instant — Paris is now cached too:

(println "\n=== Paris again (cached) ===")

(let [raw (pocket/cached #'fetch-readings {:city "Paris" :days 7 :source :api})
      clean (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; No log output — served entirely from the in-memory cache.

;; ## Inspecting the cache on disk

;; Let's look at what Pocket stored. The cache directory
;; is organized by a SHA-1 prefix, then a human-readable
;; directory named after the function and arguments.

;; ### Cache entries

(pocket/cache-entries)

;; ### Cache statistics

(pocket/cache-stats)

(kind/test-last [(fn [stats] (= 10 (:total-entries stats)))])

;; ### Directory tree
;;
;; Each entry contains either a `value.nippy` file (serialized value)
;; or a `nil` marker, plus a `meta.edn` with metadata.
;; Here is the actual cache directory tree, generated dynamically.
;;
;; Notice that some entries use human-readable directory names while
;; others fall back to SHA-1 hashes — this happens when the cache key
;; (which includes the full upstream computation chain) exceeds 240
;; characters. The `meta.edn` inside each entry always contains the
;; full details.

(pocket/dir-tree)

;; ## Cleanup

;; Remove all cached data:

(pocket/cleanup!)
