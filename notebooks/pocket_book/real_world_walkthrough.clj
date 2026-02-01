;; # Real-World Walkthrough: Weather Analysis Pipeline

(ns pocket-book.real-world-walkthrough
  (:require [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]
            [babashka.fs :as fs]))

(System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "debug")

;; This walkthrough demonstrates a realistic data pipeline with
;; multiple stages and branching dependencies. We'll simulate
;; a weather analysis workflow where raw sensor readings are
;; cleaned, then fed into two independent analyses — one for
;; temperature trends and one for rainfall totals. Both analyses
;; share the same cleaned data, so Pocket caches it once and
;; reuses it.
;;
;; The dependency graph looks like this:

^:kindly/hide-code
(kind/mermaid
 "flowchart TD
    FR[fetch-readings] --> CD[clean-data]
    CD --> TT[temperature-trends]
    CD --> RT[rainfall-totals]
    TT --> S[summary]
    RT --> S")

;; ## Setup

(def cache-dir "/tmp/pocket-walkthrough")

^:kindly/hide-code
(defn dir-tree
  "Render a directory as a tree string, like the `tree` command."
  [dir]
  (let [root (clojure.java.io/file dir)
        sb (StringBuilder.)]
    (.append sb (.getName root))
    (.append sb "\n")
    (letfn [(walk [f prefix last?]
              (.append sb prefix)
              (.append sb (if last? "└── " "├── "))
              (.append sb (.getName f))
              (.append sb "\n")
              (when (.isDirectory f)
                (let [children (sort-by #(.getName %) (vec (.listFiles f)))
                      n (count children)]
                  (doseq [[i child] (map-indexed vector children)]
                    (walk child
                          (str prefix (if last? "    " "│   "))
                          (= i (dec n)))))))]
      (let [children (sort-by #(.getName %) (vec (.listFiles root)))
            n (count children)]
        (doseq [[i child] (map-indexed vector children)]
          (walk child "" (= i (dec n))))))
    (str sb)))

(pocket/set-base-cache-dir! cache-dir)

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
   :readings [{:day 1 :temp-c 18.2 :rain-mm 0.0}
              {:day 2 :temp-c 21.5 :rain-mm 5.2}
              {:day 3 :temp-c 19.8 :rain-mm 12.1}
              {:day 4 :temp-c 22.0 :rain-mm 0.0}
              {:day 5 :temp-c 16.3 :rain-mm 8.4}
              {:day 6 :temp-c 20.1 :rain-mm 0.0}
              {:day 7 :temp-c 23.7 :rain-mm 3.3}]})

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

(let [raw    (pocket/cached #'fetch-readings {:city "London" :days 7 :source :api})
      clean  (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps  (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain   (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; Every function ran. Notice the log messages showing cache misses,
;; computations, and writes.

;; ## Second run: everything from cache

;; Running the exact same pipeline again — nothing recomputes:

(println "\n=== Second run (fully cached) ===")

(let [raw    (pocket/cached #'fetch-readings {:city "London" :days 7 :source :api})
      clean  (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps  (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain   (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; ## Changing a downstream step

;; Now suppose we want a different city. The entire pipeline
;; recomputes because the root input changed:

(println "\n=== Different city ===")

(let [raw    (pocket/cached #'fetch-readings {:city "Paris" :days 7 :source :api})
      clean  (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps  (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain   (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; But running it again is instant — Paris is now cached too:

(println "\n=== Paris again (cached) ===")

(let [raw    (pocket/cached #'fetch-readings {:city "Paris" :days 7 :source :api})
      clean  (pocket/cached #'clean-data raw {:precision 10 :remove-nulls true})
      temps  (pocket/cached #'temperature-trends clean {:unit :celsius})
      rain   (pocket/cached #'rainfall-totals clean {:unit :mm})
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; ## Inspecting the cache on disk

;; Let's look at what Pocket stored. The cache uses
;; [content-addressable storage](https://en.wikipedia.org/wiki/Content-addressable_storage):
;; a SHA-1 prefix directory, then a human-readable directory
;; named after the function and arguments.

;; ### Cache entries

(pocket/cache-entries)

;; ### Cache statistics

(pocket/cache-stats)

;; ### Directory tree
;;
;; Each entry contains either a `_.nippy` file (serialized value)
;; or a `nil` marker, plus a `_.meta.edn` with metadata.
;; Here is the actual cache directory tree, generated dynamically:

(kind/code (dir-tree (str cache-dir "/.cache")))

;; ## Cleanup

;; Remove all cached data:

(pocket/cleanup!)
