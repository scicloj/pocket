(ns
 pocket-book.concurrency-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.core.cache.wrapped :as cw]
  [clojure.test :refer [deftest is]]))


(def
 v3_l65
 (let
  [call-count
   (atom 0)
   cache
   (cw/lru-cache-factory {} :threshold 32)
   barrier
   (java.util.concurrent.CyclicBarrier. 5)
   slow-fn
   (fn [_key] (swap! call-count inc) (Thread/sleep 500) 42)]
  (let
   [futures
    (doall
     (for
      [_ (range 5)]
      (future
       (.await barrier)
       (cw/lookup-or-miss cache :same-key slow-fn))))
    results
    (mapv deref futures)]
   {:results results, :computation-count @call-count})))


(deftest
 t4_l80
 (is
  ((fn
    [{:keys [results computation-count]}]
    (and
     (every? (fn* [p1__75615#] (= 42 p1__75615#)) results)
     (> computation-count 1)))
   v3_l65)))


(def
 v6_l127
 (kind/mermaid
  "flowchart TB\n    subgraph Request\n    D[deref Cached]\n    end\n    subgraph Synchronization\n    CHM[ConcurrentHashMap<br>in-flight]\n    DEL[delay]\n    end\n    subgraph Caching\n    MEM[Memory Cache<br>core.cache]\n    DISK[Disk Cache<br>Nippy files]\n    end\n    D --> CHM\n    CHM -->|one delay per key| DEL\n    DEL -->|on miss| MEM\n    MEM -->|on miss| DISK\n    DISK -->|on miss| COMP[Compute]\n    COMP --> DISK\n    DISK --> MEM\n    MEM --> D"))


(def v8_l164 (def test-dir "/tmp/pocket-concurrency-test"))


(def v9_l166 (pocket/set-base-cache-dir! test-dir))


(def v11_l170 (def computation-count (atom 0)))


(def
 v12_l172
 (defn
  slow-computation
  "A computation that takes 300ms and increments a counter."
  [x]
  (swap! computation-count inc)
  (Thread/sleep 300)
  (* x x)))


(def
 v14_l181
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


(def v16_l210 (fresh-scenario!))


(def
 v18_l214
 (let
  [cached-val
   (pocket/cached #'slow-computation 10)
   futures
   (doall (for [_ (range 5)] (future @cached-val)))
   results
   (mapv deref futures)]
  {:results results, :computation-count @computation-count}))


(deftest
 t19_l221
 (is
  ((fn
    [{:keys [results computation-count]}]
    (and
     (= 5 (count results))
     (every? (fn* [p1__75616#] (= 100 p1__75616#)) results)
     (= 1 computation-count)))
   v18_l214)))


(def v21_l244 (fresh-scenario!))


(def
 v23_l248
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
 t24_l262
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
   v23_l248)))


(def v26_l285 (fresh-scenario!))


(def
 v28_l289
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
 t29_l304
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
   v28_l289)))


(def v31_l325 (def failure-count (atom 0)))


(def
 v32_l327
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


(def v33_l336 (do (reset! failure-count 0) (pocket/cleanup!) :ready))


(def
 v35_l343
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
 t36_l359
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
   v35_l343)))


(def v38_l386 (fresh-scenario!))


(def
 v39_l388
 (let
  [start
   (System/currentTimeMillis)
   futures
   (mapv
    (fn*
     [p1__75617#]
     (future @(pocket/cached #'slow-computation p1__75617#)))
    [40 41 42])
   results
   (mapv deref futures)
   elapsed
   (- (System/currentTimeMillis) start)]
  {:results results, :elapsed-ms elapsed, :parallel? (< elapsed 500)}))


(deftest
 t40_l397
 (is
  ((fn
    [{:keys [results elapsed-ms parallel?]}]
    (and (= [1600 1681 1764] results) (< elapsed-ms 500) parallel?))
   v39_l388)))


(def v42_l417 (fresh-scenario!))


(def
 v44_l421
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
 t45_l436
 (is
  ((fn
    [{:keys
      [results
       count-after-compute
       count-after-disk-hits
       no-recompute?]}]
    (and
     (= 3 (count results))
     (every? (fn* [p1__75618#] (= 2500 p1__75618#)) results)
     (= 1 count-after-compute)
     (= 1 count-after-disk-hits)
     no-recompute?))
   v44_l421)))


(def
 v47_l461
 (fresh-scenario! {:mem-cache-opts {:policy :lru, :threshold 2}}))


(def
 v48_l463
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
 t49_l496
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
   v48_l463)))


(def v51_l512 (fresh-scenario!))


(def
 v52_l514
 (let
  [barrier
   (java.util.concurrent.CyclicBarrier. 5)
   futures
   (doall
    (for
     [_ (range 5)]
     (future (.await barrier) @(pocket/cached #'slow-computation 70))))
   results
   (mapv deref futures)]
  {:results results, :computation-count @computation-count}))


(deftest
 t53_l523
 (is
  ((fn
    [{:keys [results computation-count]}]
    (and
     (every? (fn* [p1__75619#] (= 4900 p1__75619#)) results)
     (= 1 computation-count)))
   v52_l514)))


(def v55_l536 (def step-a-count (atom 0)))


(def v56_l537 (def step-b-count (atom 0)))


(def
 v57_l539
 (defn
  pipeline-step-a
  "First pipeline step: slow transform."
  [x]
  (swap! step-a-count inc)
  (Thread/sleep 200)
  (* x 10)))


(def
 v58_l546
 (defn
  pipeline-step-b
  "Second pipeline step: depends on step-a result."
  [data]
  (swap! step-b-count inc)
  (Thread/sleep 200)
  (+ data 1)))


(def
 v59_l553
 (do
  (reset! step-a-count 0)
  (reset! step-b-count 0)
  (pocket/cleanup!)
  (pocket/set-mem-cache-options! {:policy :lru, :threshold 10})
  :ready))


(def
 v61_l562
 (let
  [cached-a
   (pocket/cached #'pipeline-step-a 7)
   cached-b
   (pocket/cached #'pipeline-step-b cached-a)
   barrier
   (java.util.concurrent.CyclicBarrier. 5)
   futures
   (doall (for [_ (range 5)] (future (.await barrier) @cached-b)))
   results
   (mapv deref futures)]
  {:results results,
   :step-a-count @step-a-count,
   :step-b-count @step-b-count}))


(deftest
 t62_l574
 (is
  ((fn
    [{:keys [results step-a-count step-b-count]}]
    (and
     (every? (fn* [p1__75620#] (= 71 p1__75620#)) results)
     (= 1 step-a-count)
     (= 1 step-b-count)))
   v61_l562)))


(def v64_l588 (def concurrent-fail-count (atom 0)))


(def
 v65_l590
 (defn
  fail-once-computation
  "Fails when concurrent-fail-count is 0, succeeds after."
  [x]
  (let
   [n (swap! concurrent-fail-count inc)]
   (when
    (= 1 n)
    (Thread/sleep 200)
    (throw (ex-info "Transient error" {:x x})))
   (Thread/sleep 100)
   (* x x))))


(def
 v66_l600
 (do
  (reset! concurrent-fail-count 0)
  (pocket/cleanup!)
  (pocket/set-mem-cache-options! {:policy :lru, :threshold 10})
  :ready))


(def
 v68_l608
 (let
  [barrier
   (java.util.concurrent.CyclicBarrier. 5)
   futures
   (doall
    (for
     [_ (range 5)]
     (future
      (.await barrier)
      (try
       {:value @(pocket/cached #'fail-once-computation 8)}
       (catch Exception e {:error (.getMessage e)})))))
   first-results
   (mapv deref futures)
   errors
   (filterv :error first-results)
   successes
   (filterv :value first-results)]
  {:first-round-errors (count errors),
   :first-round-successes (count successes),
   :retry @(pocket/cached #'fail-once-computation 8),
   :total-calls @concurrent-fail-count}))


(deftest
 t69_l624
 (is
  ((fn
    [{:keys
      [first-round-errors first-round-successes retry total-calls]}]
    (and
     (>= first-round-errors 1)
     (= 5 (+ first-round-errors first-round-successes))
     (= 64 retry)
     (>= total-calls 2)))
   v68_l608)))


(def
 v71_l639
 (fresh-scenario! {:mem-cache-opts {:policy :lru, :threshold 2}}))


(def
 v73_l644
 (let
  [barrier
   (java.util.concurrent.CyclicBarrier. 12)
   futures
   (doall
    (for
     [x [80 81 82] _ (range 4)]
     (future (.await barrier) @(pocket/cached #'slow-computation x))))
   results
   (mapv deref futures)]
  {:results results,
   :computation-count @computation-count,
   :expected-results (vec (for [x [80 81 82] _ (range 4)] (* x x)))}))


(deftest
 t74_l658
 (is
  ((fn
    [{:keys [results computation-count expected-results]}]
    (and (= expected-results results) (= 3 computation-count)))
   v73_l644)))


(def v76_l670 (fresh-scenario!))


(def
 v78_l674
 (let
  [_
   @(pocket/cached #'slow-computation 90)
   count-after-first
   @computation-count
   _
   (pocket/invalidate! #'slow-computation 90)
   barrier
   (java.util.concurrent.CyclicBarrier. 5)
   futures
   (doall
    (for
     [_ (range 5)]
     (future (.await barrier) @(pocket/cached #'slow-computation 90))))
   results
   (mapv deref futures)
   count-after-retry
   @computation-count]
  {:results results,
   :count-after-first count-after-first,
   :count-after-retry count-after-retry}))


(deftest
 t79_l690
 (is
  ((fn
    [{:keys [results count-after-first count-after-retry]}]
    (and
     (every? (fn* [p1__75621#] (= 8100 p1__75621#)) results)
     (= 1 count-after-first)
     (= 2 count-after-retry)))
   v78_l674)))


(def v81_l726 (pocket/cleanup!))


(def v82_l727 (pocket/reset-mem-cache-options!))
