(def
 v1_l25
 ["flowchart TD\n    FR[fetch-readings] --> CD[clean-data]\n    CD --> TT[temperature-trends]\n    CD --> RT[rainfall-totals]\n    TT --> S[summary]\n    RT --> S"])


(ns
 pocket-book.real-world-walkthrough-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [babashka.fs :as fs]
  [clojure.test :refer [deftest is]]))


(def v4_l45 (def cache-dir "/tmp/pocket-walkthrough"))


(def v5_l47 (pocket/set-base-cache-dir! cache-dir))


(def v6_l49 (pocket/cleanup!))


(def
 v8_l57
 (defn
  fetch-readings
  "Simulate fetching raw sensor data for a city."
  [opts]
  (println "  Fetching readings for" (:city opts) "...")
  (Thread/sleep 300)
  {:city (:city opts),
   :source (:source opts),
   :readings
   (case
    (:city opts)
    "Paris"
    [{:day 1, :temp-c 15.0, :rain-mm 2.1}
     {:day 2, :temp-c 17.3, :rain-mm 0.0}
     {:day 3, :temp-c 14.6, :rain-mm 7.8}
     {:day 4, :temp-c 18.1, :rain-mm 0.0}
     {:day 5, :temp-c 13.9, :rain-mm 4.5}
     {:day 6, :temp-c 16.7, :rain-mm 0.0}
     {:day 7, :temp-c 19.2, :rain-mm 1.0}]
    [{:day 1, :temp-c 18.2, :rain-mm 0.0}
     {:day 2, :temp-c 21.5, :rain-mm 5.2}
     {:day 3, :temp-c 19.8, :rain-mm 12.1}
     {:day 4, :temp-c 22.0, :rain-mm 0.0}
     {:day 5, :temp-c 16.3, :rain-mm 8.4}
     {:day 6, :temp-c 20.1, :rain-mm 0.0}
     {:day 7, :temp-c 23.7, :rain-mm 3.3}])}))


(def
 v9_l82
 (defn
  clean-data
  "Remove readings with missing values and round numbers."
  [raw-data opts]
  (println "  Cleaning data for" (:city raw-data) "with" opts "...")
  (Thread/sleep 200)
  (let
   [precision (:precision opts 10)]
   (update
    raw-data
    :readings
    (fn
     [rs]
     (->>
      rs
      (filter
       (fn*
        [p1__20752#]
        (and (:temp-c p1__20752#) (:rain-mm p1__20752#))))
      (mapv
       (fn*
        [p1__20753#]
        (->
         p1__20753#
         (update
          :temp-c
          (fn [t] (Math/round (* t (double precision)))))
         (update
          :rain-mm
          (fn [r] (Math/round (* r (double precision))))))))))))))


(def
 v10_l96
 (defn
  temperature-trends
  "Compute temperature statistics from cleaned data."
  [clean-data opts]
  (println "  Analyzing temperature for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let
   [temps (map :temp-c (:readings clean-data)) n (count temps)]
   {:city (:city clean-data),
    :unit (:unit opts),
    :min-temp (apply min temps),
    :max-temp (apply max temps),
    :mean-temp (quot (reduce + temps) n),
    :days n})))


(def
 v11_l110
 (defn
  rainfall-totals
  "Compute rainfall statistics from cleaned data."
  [clean-data opts]
  (println "  Analyzing rainfall for" (:city clean-data) "...")
  (Thread/sleep 300)
  (let
   [rains
    (map :rain-mm (:readings clean-data))
    rainy-days
    (count (filter pos? rains))]
   {:city (:city clean-data),
    :unit (:unit opts),
    :total-rain (reduce + rains),
    :rainy-days rainy-days,
    :dry-days (- (count rains) rainy-days)})))


(def
 v12_l123
 (defn
  summary
  "Combine temperature and rainfall analyses into a report."
  [temp-analysis rain-analysis]
  (println "  Generating summary for" (:city temp-analysis) "...")
  (Thread/sleep 200)
  (merge
   temp-analysis
   rain-analysis
   {:report
    (str
     (:city temp-analysis)
     ": temp range "
     (:min-temp temp-analysis)
     "–"
     (:max-temp temp-analysis)
     ", total rain "
     (:total-rain rain-analysis)
     " over "
     (:rainy-days rain-analysis)
     " days")})))


(def v14_l144 (println "=== First run ==="))


(def
 v15_l146
 (let
  [raw
   (pocket/cached
    #'fetch-readings
    {:city "London", :days 7, :source :api})
   clean
   (pocket/cached #'clean-data raw {:precision 10, :remove-nulls true})
   temps
   (pocket/cached #'temperature-trends clean {:unit :celsius})
   rain
   (pocket/cached #'rainfall-totals clean {:unit :mm})
   report
   (pocket/cached #'summary temps rain)]
  (time (deref report))))


(deftest
 t16_l153
 (is
  ((fn
    [result]
    (every?
     result
     [:city :report :min-temp :max-temp :total-rain :rainy-days]))
   v15_l146)))


(def v18_l162 (println "\n=== Second run (fully cached) ==="))


(def
 v19_l164
 (let
  [raw
   (pocket/cached
    #'fetch-readings
    {:city "London", :days 7, :source :api})
   clean
   (pocket/cached #'clean-data raw {:precision 10, :remove-nulls true})
   temps
   (pocket/cached #'temperature-trends clean {:unit :celsius})
   rain
   (pocket/cached #'rainfall-totals clean {:unit :mm})
   report
   (pocket/cached #'summary temps rain)]
  (time (deref report))))


(deftest
 t20_l171
 (is ((fn [result] (= "London" (:city result))) v19_l164)))


(def v22_l180 (println "\n=== Different city ==="))


(def
 v23_l182
 (let
  [raw
   (pocket/cached
    #'fetch-readings
    {:city "Paris", :days 7, :source :api})
   clean
   (pocket/cached #'clean-data raw {:precision 10, :remove-nulls true})
   temps
   (pocket/cached #'temperature-trends clean {:unit :celsius})
   rain
   (pocket/cached #'rainfall-totals clean {:unit :mm})
   report
   (pocket/cached #'summary temps rain)]
  (time (deref report))))


(deftest
 t24_l189
 (is ((fn [result] (= "Paris" (:city result))) v23_l182)))


(def v26_l193 (println "\n=== Paris again (cached) ==="))


(def
 v27_l195
 (let
  [raw
   (pocket/cached
    #'fetch-readings
    {:city "Paris", :days 7, :source :api})
   clean
   (pocket/cached #'clean-data raw {:precision 10, :remove-nulls true})
   temps
   (pocket/cached #'temperature-trends clean {:unit :celsius})
   rain
   (pocket/cached #'rainfall-totals clean {:unit :mm})
   report
   (pocket/cached #'summary temps rain)]
  (time (deref report))))


(def v29_l212 (pocket/cache-entries))


(def v31_l216 (pocket/cache-stats))


(deftest
 t32_l218
 (is ((fn [stats] (= 10 (:total-entries stats))) v31_l216)))


(def v34_l232 (pocket/dir-tree))


(def v36_l238 (pocket/cleanup!))
