(ns
 pocket-book.concurrency-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def
 v3_l89
 (kind/mermaid
  "flowchart TB\n    subgraph Request\n    D[deref Cached]\n    end\n    subgraph Synchronization\n    CHM[ConcurrentHashMap<br>in-flight]\n    DEL[delay]\n    end\n    subgraph Caching\n    MEM[Memory Cache<br>core.cache]\n    DISK[Disk Cache<br>Nippy files]\n    end\n    D --> CHM\n    CHM -->|one delay per key| DEL\n    DEL -->|on miss| MEM\n    MEM -->|on miss| DISK\n    DISK -->|on miss| COMP[Compute]\n    COMP --> DISK\n    DISK --> MEM\n    MEM --> D"))


(def v5_l126 (def test-dir "/tmp/pocket-concurrency-test"))


(def v6_l128 (pocket/set-base-cache-dir! test-dir))


(def v8_l132 (def computation-count (atom 0)))


(def
 v9_l134
 (defn
  slow-computation
  "A computation that takes 300ms and increments a counter."
  [x]
  (swap! computation-count inc)
  (Thread/sleep 300)
  (* x x)))


(def
 v11_l143
 (defn
  fresh-scenario!
  "Reset counters and caches for a fresh scenario.\n   Returns the start time for timing measurements."
  ([] (fresh-scenario! {}))
  ([{:keys [mem-cache-opts],
     :or {mem-cache-opts {:policy :lru, :threshold 3}}}]
   (reset! computation-count 0)
   (pocket/cleanup!)
   (pocket/set-mem-cache-options! mem-cache-opts)
   {:started-at (java.time.LocalTime/now), :mem-cache mem-cache-opts})))


(def v13_l172 (fresh-scenario!))


(def
 v15_l176
 (let
  [cached-val
   (pocket/cached #'slow-computation 10)
   futures
   (doall (for [_ (range 5)] (future @cached-val)))
   results
   (mapv deref futures)]
  {:results results, :computation-count @computation-count}))


(deftest
 t16_l183
 (is
  ((fn
    [{:keys [results computation-count]}]
    (and
     (= 5 (count results))
     (every? (fn* [p1__41685#] (= 100 p1__41685#)) results)
     (= 1 computation-count)))
   v15_l176)))


(def v18_l206 (fresh-scenario!))


(def
 v20_l210
 (let
  [result-1
   @(pocket/cached #'slow-computation 20)
   count-after-first
   @computation-count
   start
   (System/currentTimeMillis)
   result-2
   @(pocket/cached #'slow-computation 20)
   elapsed
   (- (System/currentTimeMillis) start)
   count-after-second
   @computation-count]
  {:first-result result-1,
   :second-result result-2,
   :second-elapsed-ms elapsed,
   :computations-after-first count-after-first,
   :computations-after-second count-after-second}))


(deftest
 t21_l224
 (is
  ((fn
    [{:keys
      [first-result
       second-result
       second-elapsed-ms
       computations-after-first
       computations-after-second]}]
    (and
     (= 400 first-result)
     (= 400 second-result)
     (< second-elapsed-ms 50)
     (= 1 computations-after-first)
     (= 1 computations-after-second)))
   v20_l210)))


(def v23_l247 (fresh-scenario!))


(def
 v25_l251
 (let
  [_
   @(pocket/cached #'slow-computation 30)
   _
   @(pocket/cached #'slow-computation 31)
   _
   @(pocket/cached #'slow-computation 32)
   _
   @(pocket/cached #'slow-computation 33)
   count-before-retry
   @computation-count
   result
   @(pocket/cached #'slow-computation 30)
   count-after-retry
   @computation-count]
  {:result result,
   :computations-before-retry count-before-retry,
   :computations-after-retry count-after-retry,
   :disk-hit? (= count-before-retry count-after-retry)}))


(deftest
 t26_l266
 (is
  ((fn
    [{:keys
      [result
       computations-before-retry
       computations-after-retry
       disk-hit?]}]
    (and
     (= 900 result)
     (= 4 computations-before-retry)
     (= 4 computations-after-retry)
     disk-hit?))
   v25_l251)))


(def v28_l287 (def failure-count (atom 0)))


(def
 v29_l289
 (defn
  flaky-computation
  "Fails on first call, succeeds thereafter."
  [x]
  (if
   (zero? @failure-count)
   (do
    (swap! failure-count inc)
    (throw (ex-info "Temporary failure" {:x x})))
   (do (swap! failure-count inc) (* x 100)))))


(def v30_l298 (do (reset! failure-count 0) (pocket/cleanup!) :ready))


(def
 v32_l305
 (let
  [attempt-1
   (try
    @(pocket/cached #'flaky-computation 5)
    (catch Exception e {:error (.getMessage e)}))
   count-after-1
   @failure-count
   attempt-2
   @(pocket/cached #'flaky-computation 5)
   count-after-2
   @failure-count
   attempt-3
   @(pocket/cached #'flaky-computation 5)
   count-after-3
   @failure-count]
  {:attempt-1 attempt-1,
   :attempt-2 attempt-2,
   :attempt-3 attempt-3,
   :counts [count-after-1 count-after-2 count-after-3]}))


(deftest
 t33_l321
 (is
  ((fn
    [{:keys [attempt-1 attempt-2 attempt-3 counts]}]
    (and
     (map? attempt-1)
     (contains? attempt-1 :error)
     (= 500 attempt-2)
     (= 500 attempt-3)
     (= 1 (first counts))
     (= 2 (second counts))
     (= 2 (nth counts 2))))
   v32_l305)))


(def v35_l348 (fresh-scenario!))


(def
 v36_l350
 (let
  [start
   (System/currentTimeMillis)
   futures
   (mapv
    (fn*
     [p1__41686#]
     (future @(pocket/cached #'slow-computation p1__41686#)))
    [40 41 42])
   results
   (mapv deref futures)
   elapsed
   (- (System/currentTimeMillis) start)]
  {:results results, :elapsed-ms elapsed, :parallel? (< elapsed 500)}))


(deftest
 t37_l359
 (is
  ((fn
    [{:keys [results elapsed-ms parallel?]}]
    (and (= [1600 1681 1764] results) (< elapsed-ms 500) parallel?))
   v36_l350)))


(def v39_l379 (fresh-scenario!))


(def
 v41_l383
 (let
  [_
   @(pocket/cached #'slow-computation 50)
   count-after-compute
   @computation-count
   _
   (pocket/clear-mem-cache!)
   futures
   (mapv
    (fn [_] (future @(pocket/cached #'slow-computation 50)))
    (range 3))
   results
   (mapv deref futures)
   count-after-disk-hits
   @computation-count]
  {:results results,
   :count-after-compute count-after-compute,
   :count-after-disk-hits count-after-disk-hits,
   :no-recompute? (= count-after-compute count-after-disk-hits)}))


(deftest
 t42_l398
 (is
  ((fn
    [{:keys
      [results
       count-after-compute
       count-after-disk-hits
       no-recompute?]}]
    (and
     (= 3 (count results))
     (every? (fn* [p1__41687#] (= 2500 p1__41687#)) results)
     (= 1 count-after-compute)
     (= 1 count-after-disk-hits)
     no-recompute?))
   v41_l383)))


(def
 v44_l423
 (fresh-scenario! {:mem-cache-opts {:policy :lru, :threshold 2}}))


(def
 v45_l425
 (let
  [_
   @(pocket/cached #'slow-computation 60)
   count-step-1
   @computation-count
   start-2
   (System/currentTimeMillis)
   _
   @(pocket/cached #'slow-computation 60)
   elapsed-2
   (- (System/currentTimeMillis) start-2)
   count-step-2
   @computation-count
   _
   @(pocket/cached #'slow-computation 61)
   _
   @(pocket/cached #'slow-computation 62)
   count-step-3
   @computation-count
   _
   @(pocket/cached #'slow-computation 60)
   count-step-4
   @computation-count
   _
   (pocket/invalidate! #'slow-computation 60)
   _
   (pocket/clear-mem-cache!)
   _
   @(pocket/cached #'slow-computation 60)
   count-step-6
   @computation-count]
  {:count-step-1 count-step-1,
   :elapsed-step-2 elapsed-2,
   :count-step-2 count-step-2,
   :count-step-3 count-step-3,
   :count-step-4 count-step-4,
   :count-step-6 count-step-6}))


(deftest
 t46_l458
 (is
  ((fn
    [{:keys
      [count-step-1
       elapsed-step-2
       count-step-2
       count-step-3
       count-step-4
       count-step-6]}]
    (and
     (= 1 count-step-1)
     (< elapsed-step-2 50)
     (= 1 count-step-2)
     (= 3 count-step-3)
     (= 3 count-step-4)
     (= 4 count-step-6)))
   v45_l425)))


(def v48_l498 (pocket/cleanup!))


(def v49_l499 (pocket/reset-mem-cache-options!))
