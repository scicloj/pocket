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

(pocket/set-base-cache-dir! cache-dir)

;; ## Pipeline functions

;; Each function simulates real work with a short delay.
;; Note that these are plain Clojure functions — they know
;; nothing about caching.

(defn fetch-readings
  "Simulate fetching raw sensor data for a city."
  [city]
  (println "  Fetching readings for" city "...")
  (Thread/sleep 300)
  {:city city
   :readings [{:day 1 :temp-c 18.2 :rain-mm 0.0}
              {:day 2 :temp-c 21.5 :rain-mm 5.2}
              {:day 3 :temp-c 19.8 :rain-mm 12.1}
              {:day 4 :temp-c 22.0 :rain-mm 0.0}
              {:day 5 :temp-c 16.3 :rain-mm 8.4}
              {:day 6 :temp-c 20.1 :rain-mm 0.0}
              {:day 7 :temp-c 23.7 :rain-mm 3.3}]})

(defn clean-data
  "Remove readings with missing values and round numbers."
  [raw-data]
  (println "  Cleaning data for" (:city raw-data) "...")
  (Thread/sleep 200)
  (update raw-data :readings
          (fn [rs]
            (->> rs
                 (filter #(and (:temp-c %) (:rain-mm %)))
                 (mapv #(-> %
                            (update :temp-c (fn [t] (Math/round (* t 10.0))))
                            (update :rain-mm (fn [r] (Math/round (* r 10.0))))))))))

(defn temperature-trends
  "Compute temperature statistics from cleaned data."
  [clean-data]
  (println "  Analyzing temperature for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let [temps (map :temp-c (:readings clean-data))
        n (count temps)]
    {:city (:city clean-data)
     :min-temp-c10 (apply min temps)
     :max-temp-c10 (apply max temps)
     :mean-temp-c10 (quot (reduce + temps) n)
     :days n}))

(defn rainfall-totals
  "Compute rainfall statistics from cleaned data."
  [clean-data]
  (println "  Analyzing rainfall for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let [rains (map :rain-mm (:readings clean-data))
        rainy-days (count (filter pos? rains))]
    {:city (:city clean-data)
     :total-rain-mm10 (reduce + rains)
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
                       (/ (:min-temp-c10 temp-analysis) 10.0) "–"
                       (/ (:max-temp-c10 temp-analysis) 10.0) "°C"
                       ", total rain "
                       (/ (:total-rain-mm10 rain-analysis) 10.0) "mm"
                       " over " (:rainy-days rain-analysis) " days")}))

;; ## First run: everything computes

;; We build the pipeline using `cached`. Each call returns
;; a `Cached` object that we pass directly to the next stage.
;; Pocket derives cache keys from the computation graph, not
;; from intermediate values.

(println "=== First run ===")

(let [raw (pocket/cached #'fetch-readings "London")
      clean (pocket/cached #'clean-data raw)
      temps (pocket/cached #'temperature-trends clean)
      rain (pocket/cached #'rainfall-totals clean)
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; Every function ran. Notice the log messages showing cache misses,
;; computations, and writes.

;; ## Second run: everything from cache

;; Running the exact same pipeline again — nothing recomputes:

(println "\n=== Second run (fully cached) ===")

(let [raw (pocket/cached #'fetch-readings "London")
      clean (pocket/cached #'clean-data raw)
      temps (pocket/cached #'temperature-trends clean)
      rain (pocket/cached #'rainfall-totals clean)
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; ## Changing a downstream step

;; Now suppose we want a different city. The entire pipeline
;; recomputes because the root input changed:

(println "\n=== Different city ===")

(let [raw (pocket/cached #'fetch-readings "Paris")
      clean (pocket/cached #'clean-data raw)
      temps (pocket/cached #'temperature-trends clean)
      rain (pocket/cached #'rainfall-totals clean)
      report (pocket/cached #'summary temps rain)]
  (time (deref report)))

;; But running it again is instant — Paris is now cached too:

(println "\n=== Paris again (cached) ===")

(let [raw (pocket/cached #'fetch-readings "Paris")
      clean (pocket/cached #'clean-data raw)
      temps (pocket/cached #'temperature-trends clean)
      rain (pocket/cached #'rainfall-totals clean)
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
;; or a `nil` marker, plus a `_.meta.edn` with metadata:

(let [cache-root (str cache-dir "/.cache")]
  (->> (file-seq (clojure.java.io/file cache-root))
       (filter #(.isFile %))
       (mapv #(str (.relativize
                    (.toPath (clojure.java.io/file cache-root))
                    (.toPath %))))))

;; ## Cleanup

;; Remove all cached data:

(pocket/cleanup!)
