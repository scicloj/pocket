(ns
 pocket-book.real-world-walkthrough-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [babashka.fs :as fs]
  [clojure.test :refer [deftest is]]))


(def
 v3_l27
 (kind/mermaid
  "flowchart TD\n    FR[fetch-readings] --> CD[clean-data]\n    CD --> TT[temperature-trends]\n    CD --> RT[rainfall-totals]\n    TT --> S[summary]\n    RT --> S"))


(def v5_l38 (def cache-dir "/tmp/pocket-walkthrough"))


(def v6_l40 (pocket/set-base-cache-dir! cache-dir))


(def v7_l42 (pocket/cleanup!))


(def
 v9_l50
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
 v10_l75
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
        [p1__28247#]
        (and (:temp-c p1__28247#) (:rain-mm p1__28247#))))
      (mapv
       (fn*
        [p1__28248#]
        (->
         p1__28248#
         (update
          :temp-c
          (fn [t] (Math/round (* t (double precision)))))
         (update
          :rain-mm
          (fn [r] (Math/round (* r (double precision))))))))))))))


(def
 v11_l89
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
 v12_l103
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
 v13_l116
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


(def v15_l137 (println "=== First run ==="))


(def
 v16_l139
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
 t17_l146
 (is
  ((fn
    [result]
    (every?
     result
     [:city :report :min-temp :max-temp :total-rain :rainy-days]))
   v16_l139)))


(def v19_l155 (println "\n=== Second run (fully cached) ==="))


(def
 v20_l157
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
 t21_l164
 (is ((fn [result] (= "London" (:city result))) v20_l157)))


(def v23_l173 (println "\n=== Different city ==="))


(def
 v24_l175
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
 t25_l182
 (is ((fn [result] (= "Paris" (:city result))) v24_l175)))


(def v27_l186 (println "\n=== Paris again (cached) ==="))


(def
 v28_l188
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


(def v30_l205 (pocket/cache-entries))


(def v32_l209 (pocket/cache-stats))


(deftest
 t33_l211
 (is ((fn [stats] (= 10 (:total-entries stats))) v32_l209)))


(def v35_l219 (kind/code (pocket/dir-tree)))


(def v37_l225 (pocket/cleanup!))
