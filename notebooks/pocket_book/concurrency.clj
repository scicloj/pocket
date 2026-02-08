;; # Concurrency
;;
;; **Last modified: 2026-02-08**


(ns pocket-book.concurrency
  (:require
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Cache implementation for comparisons:
   [clojure.core.cache.wrapped :as cw]))

;; Pocket guarantees that when multiple threads deref the same `Cached` value
;; concurrently, the underlying computation executes **exactly once**.
;; This chapter explains how that guarantee is achieved and demonstrates
;; the concurrency scenarios it handles.

;; ## The Challenge

;; The naive approach to caching — check if cached, compute if not — has a race condition:
;;
;; ```
;; Thread A                    Thread B
;; ────────                    ────────
;; check cache → miss          check cache → miss
;; compute value               compute value     ← duplicate!
;; store result                store result
;; ```
;;
;; Both threads see the cache miss and both compute the value.
;; For expensive computations (minutes, hours), this wastes resources.

;; ## Why `core.cache` Alone Is Not Enough

;; Clojure's [core.cache](https://github.com/clojure/core.cache) provides
;; `lookup-or-miss` which wraps the value function in a delay to prevent
;; duplicate work across `swap!` retries within a single call.
;; However, each call to `lookup-or-miss` creates its **own** delay,
;; and the value function is evaluated **inside** the `swap!` body
;; (via `through-cache`). This means concurrent callers racing into
;; `swap!` can each see a miss and each start computing before any
;; compare-and-swap succeeds:
;;
;; ```
;; Thread A                          Thread B
;; ────────                          ────────
;; lookup-or-miss
;;   create delay-A
;;   swap!                           lookup-or-miss
;;     through-cache → miss            create delay-B
;;     @delay-A → computing...         swap!
;;                                       through-cache → miss
;;                                       @delay-B → computing... ← duplicate!
;; ```
;;
;; The `swap!` compare-and-swap ensures only one result enters the cache,
;; but both computations have already started. The delay prevents
;; redundant work across retries of a **single** `swap!` call — it does
;; **not** deduplicate across concurrent callers.

;; ## Seeing the Problem

;; We can demonstrate this directly. Here we use `core.cache.wrapped/lookup-or-miss`
;; with a slow computation and five concurrent threads.
;; A `CyclicBarrier` synchronizes the threads so they all call
;; `lookup-or-miss` at the same instant:

(let [call-count (atom 0)
      cache (cw/lru-cache-factory {} :threshold 32)
      barrier (java.util.concurrent.CyclicBarrier. 5)
      slow-fn (fn [_key]
                (swap! call-count inc)
                (Thread/sleep 500)
                42)]
  (let [futures (doall (for [_ (range 5)]
                         (future
                           (.await barrier)
                           (cw/lookup-or-miss cache :same-key slow-fn))))
        results (mapv deref futures)]
    {:results results
     :computation-count @call-count}))

(kind/test-last
 [(fn [{:keys [results computation-count]}]
    (and (every? #(= 42 %) results)
         (> computation-count 1)))])

;; All five threads computed the value independently — `computation-count`
;; is greater than 1 (typically 5). The delay inside `lookup-or-miss`
;; prevented duplicate work on `swap!` retries within each thread, but
;; concurrent callers each created and forced their own delay.
;;
;; Scenario 1 (below) repeats this same pattern using Pocket,
;; where the `ConcurrentHashMap` layer reduces the count to exactly 1.

;; ## Pocket's Solution

;; Pocket adds a `ConcurrentHashMap` layer that ensures only **one delay**
;; exists per cache key, regardless of how many threads request it:
;;
;; ```clojure
;; (def ^ConcurrentHashMap in-flight
;;   (java.util.concurrent.ConcurrentHashMap.))
;;
;; ;; Inside the lookup-or-miss miss-fn:
;; (let [d (.computeIfAbsent
;;           in-flight path
;;           (fn [_]
;;             (delay
;;               (try
;;                 ;; disk check + computation
;;                 (finally
;;                   (.remove in-flight path))))))]
;;   @d)
;; ```
;;
;; `computeIfAbsent` is atomic: the first thread creates and inserts the
;; delay; all subsequent threads for the same key receive the **same**
;; delay instance. Since a Clojure `delay` executes its body exactly once,
;; the computation runs once and all threads share the result.

;; ### Failure Handling

;; The `finally` block removes the entry from `in-flight` after computation
;; (success or failure). If a computation throws, the next caller gets a
;; fresh delay and a fresh attempt — exceptions are never cached.

;; ## Architecture Layers

^:kindly/hide-code
(kind/mermaid
 "flowchart TB
    subgraph Request
    D[deref Cached]
    end
    subgraph Synchronization
    CHM[ConcurrentHashMap<br>in-flight]
    DEL[delay]
    end
    subgraph Caching
    MEM[Memory Cache<br>core.cache]
    DISK[Disk Cache<br>Nippy files]
    end
    D --> CHM
    CHM -->|one delay per key| DEL
    DEL -->|on miss| MEM
    MEM -->|on miss| DISK
    DISK -->|on miss| COMP[Compute]
    COMP --> DISK
    DISK --> MEM
    MEM --> D")

;; | Layer | Purpose | Guarantee |
;; |-------|---------|-----------|
;; | ConcurrentHashMap | Delay creation | One delay per key |
;; | delay | Computation | One execution per delay |
;; | core.cache (mem-cache) | In-memory caching | Fast repeated access |
;; | Disk cache | Persistence | Cross-session caching |

;; ## Concurrency Scenarios

;; The following scenarios demonstrate Pocket's thread-safety guarantees
;; with various timing patterns.

;; ### Setup

(def test-dir "/tmp/pocket-concurrency-test")

(pocket/set-base-cache-dir! test-dir)

;; A counter to track how many times computation actually runs:

(def computation-count (atom 0))

(defn slow-computation
  "A computation that takes 300ms and increments a counter."
  [x]
  (swap! computation-count inc)
  (Thread/sleep 300)
  (* x x))

;; Convenience function to reset state before each scenario:

(defn fresh-scenario!
  "Reset counters and caches for a fresh scenario.
   Returns the start time for timing measurements."
  ([]
   (fresh-scenario! {}))
  ([{:keys [mem-cache-opts]
     :or {mem-cache-opts {:policy :lru :threshold 3}}}]
   (reset! computation-count 0)
   (pocket/cleanup!)
   (pocket/set-mem-cache-options! mem-cache-opts)
   {:started-at (java.time.LocalTime/now)
    :mem-cache mem-cache-opts}))

;; ---

;; ### Scenario 1: Concurrent Deref of Same Value

;; Multiple threads deref the same `Cached` object while the computation
;; is still running. All should receive the same result from a single computation.
;;
;; ```
;; Timeline (ms):   0         100        300        400
;;                  │          │          │          │
;; Thread A:       [─── request ───][─── computing ───]──→ result
;; Thread B:                  [─── request ───][ wait ]──→ result
;;                                              ↑
;;                              B waits for A's computation
;; ```

(fresh-scenario!)

;; Launch 5 threads that all deref the same cached value:

(let [cached-val (pocket/cached #'slow-computation 10)
      futures (doall (for [_ (range 5)]
                       (future @cached-val)))
      results (mapv deref futures)]
  {:results results
   :computation-count @computation-count})

(kind/test-last
 [(fn [{:keys [results computation-count]}]
    (and (= 5 (count results))
         (every? #(= 100 %) results)
         (= 1 computation-count)))])

;; ---

;; ### Scenario 2: Memory Cache Hit

;; After the first computation, subsequent requests hit the memory cache
;; instantly (no disk I/O, no recomputation).
;;
;; ```
;; Timeline:
;; Thread A:  [── computing ──]
;;                           ↓
;;                      mem-cache populated
;; Thread B:                         [request]──→ instant result
;;                                       ↑
;;                                 memory cache hit
;; ```

(fresh-scenario!)

;; First request computes, second is instant (memory hit):

(let [;; First request - computes
      result-1 @(pocket/cached #'slow-computation 20)
      count-after-first @computation-count
      ;; Second request - should hit memory cache
      start (System/currentTimeMillis)
      result-2 @(pocket/cached #'slow-computation 20)
      elapsed (- (System/currentTimeMillis) start)
      count-after-second @computation-count]
  {:first-result result-1
   :second-result result-2
   :second-elapsed-ms elapsed
   :computations-after-first count-after-first
   :computations-after-second count-after-second})

(kind/test-last
 [(fn [{:keys [first-result second-result second-elapsed-ms
               computations-after-first computations-after-second]}]
    (and (= 400 first-result)
         (= 400 second-result)
         (< second-elapsed-ms 50)
         (= 1 computations-after-first)
         (= 1 computations-after-second)))])

;; ---

;; ### Scenario 3: Disk Cache Hit After Memory Eviction

;; Fill the memory cache to evict our entry, then verify
;; the next request reads from disk (not recomputes).
;;
;; ```
;; Timeline:
;; 1. Compute value for arg=30          → stored in mem + disk
;; 2. Compute 3 more values (31,32,33)  → arg=30 evicted from mem (LRU)
;; 3. Request arg=30 again              → disk cache hit (no recompute)
;; ```

(fresh-scenario!)

;; Step 1: Compute initial value, then fill cache to cause eviction:

(let [;; Compute arg=30
      _ @(pocket/cached #'slow-computation 30)
      ;; Fill cache to evict arg=30 (threshold=3)
      _ @(pocket/cached #'slow-computation 31)
      _ @(pocket/cached #'slow-computation 32)
      _ @(pocket/cached #'slow-computation 33)
      count-before-retry @computation-count
      ;; Request arg=30 again - should hit disk
      result @(pocket/cached #'slow-computation 30)
      count-after-retry @computation-count]
  {:result result
   :computations-before-retry count-before-retry
   :computations-after-retry count-after-retry
   :disk-hit? (= count-before-retry count-after-retry)})

(kind/test-last
 [(fn [{:keys [result computations-before-retry computations-after-retry disk-hit?]}]
    (and (= 900 result)
         (= 4 computations-before-retry)
         (= 4 computations-after-retry)
         disk-hit?))])

;; ---

;; ### Scenario 4: Failure and Retry

;; When a computation fails, the exception is not cached.
;; The next caller gets a fresh attempt.
;;
;; ```
;; Timeline:
;; 1. Thread A requests → computation fails (exception)
;; 2. Thread B requests → fresh computation succeeds
;; 3. Thread C requests → cache hit (no recompute)
;; ```

(def failure-count (atom 0))

(defn flaky-computation
  "Fails on first call, succeeds thereafter."
  [x]
  (if (zero? @failure-count)
    (do (swap! failure-count inc)
        (throw (ex-info "Temporary failure" {:x x})))
    (do (swap! failure-count inc)
        (* x 100))))

(do
  (reset! failure-count 0)
  (pocket/cleanup!)
  :ready)

;; First attempt fails, second succeeds, third hits cache:

(let [;; First attempt - fails
      attempt-1 (try
                  @(pocket/cached #'flaky-computation 5)
                  (catch Exception e {:error (.getMessage e)}))
      count-after-1 @failure-count
      ;; Second attempt - succeeds
      attempt-2 @(pocket/cached #'flaky-computation 5)
      count-after-2 @failure-count
      ;; Third attempt - cache hit
      attempt-3 @(pocket/cached #'flaky-computation 5)
      count-after-3 @failure-count]
  {:attempt-1 attempt-1
   :attempt-2 attempt-2
   :attempt-3 attempt-3
   :counts [count-after-1 count-after-2 count-after-3]})

(kind/test-last
 [(fn [{:keys [attempt-1 attempt-2 attempt-3 counts]}]
    (and (map? attempt-1)
         (contains? attempt-1 :error)
         (= 500 attempt-2)
         (= 500 attempt-3)
         (= 1 (first counts))
         (= 2 (second counts))
         (= 2 (nth counts 2))))])

;; ---

;; ### Scenario 5: Different Arguments Compute in Parallel

;; Requests with different arguments run in parallel
;; (no unnecessary serialization).
;;
;; ```
;; Timeline (ms):   0                   300
;;                  │                    │
;; Thread A (x=40): [──── computing ────]──→ 1600
;; Thread B (x=41): [──── computing ────]──→ 1681
;; Thread C (x=42): [──── computing ────]──→ 1764
;;                   ↑
;;             All start ~simultaneously, run in parallel
;; ```

(fresh-scenario!)

(let [start (System/currentTimeMillis)
      futures (mapv #(future @(pocket/cached #'slow-computation %))
                    [40 41 42])
      results (mapv deref futures)
      elapsed (- (System/currentTimeMillis) start)]
  {:results results
   :elapsed-ms elapsed
   :parallel? (< elapsed 500)})

(kind/test-last
 [(fn [{:keys [results elapsed-ms parallel?]}]
    (and (= [1600 1681 1764] results)
         (< elapsed-ms 500)
         parallel?))])

;; ---

;; ### Scenario 6: Disk Hit with Empty Memory Cache

;; Clear memory cache while keeping disk cache.
;; All requests should read from disk without recomputing.
;;
;; ```
;; Setup: value for arg=50 is on disk but NOT in memory
;;
;; Threads A, B, C all request x=50
;; → All read from disk (no computation)
;; ```

(fresh-scenario!)

;; Compute, clear memory, then hit disk:

(let [;; Compute and cache arg=50
      _ @(pocket/cached #'slow-computation 50)
      count-after-compute @computation-count
      ;; Clear only memory cache (disk remains)
      _ (pocket/clear-mem-cache!)
      ;; Multiple threads hit disk cache
      futures (mapv (fn [_] (future @(pocket/cached #'slow-computation 50)))
                    (range 3))
      results (mapv deref futures)
      count-after-disk-hits @computation-count]
  {:results results
   :count-after-compute count-after-compute
   :count-after-disk-hits count-after-disk-hits
   :no-recompute? (= count-after-compute count-after-disk-hits)})

(kind/test-last
 [(fn [{:keys [results count-after-compute count-after-disk-hits no-recompute?]}]
    (and (= 3 (count results))
         (every? #(= 2500 %) results)
         (= 1 count-after-compute)
         (= 1 count-after-disk-hits)
         no-recompute?))])

;; ---

;; ### Scenario 7: Full Cache Hierarchy Test

;; A comprehensive scenario testing all cache layers:
;;
;; ```
;; ┌─────────────────────────────────────────────────────────────┐
;; │ Step 1: Request x=60         → COMPUTE (miss everywhere)    │
;; │ Step 2: Request x=60 again   → MEMORY HIT (instant)         │
;; │ Step 3: Evict from memory    → (fill cache with other vals) │
;; │ Step 4: Request x=60         → DISK HIT (read from disk)    │
;; │ Step 5: Delete disk cache    → invalidate!                  │
;; │ Step 6: Request x=60         → COMPUTE (miss everywhere)    │
;; └─────────────────────────────────────────────────────────────┘
;; ```

(fresh-scenario! {:mem-cache-opts {:policy :lru :threshold 2}})

(let [;; Step 1: Initial computation
      _ @(pocket/cached #'slow-computation 60)
      count-step-1 @computation-count

      ;; Step 2: Memory hit (should be instant)
      start-2 (System/currentTimeMillis)
      _ @(pocket/cached #'slow-computation 60)
      elapsed-2 (- (System/currentTimeMillis) start-2)
      count-step-2 @computation-count

      ;; Step 3: Evict from memory by filling cache
      _ @(pocket/cached #'slow-computation 61)
      _ @(pocket/cached #'slow-computation 62)
      count-step-3 @computation-count

      ;; Step 4: Disk hit (memory miss)
      _ @(pocket/cached #'slow-computation 60)
      count-step-4 @computation-count

      ;; Step 5: Delete disk cache
      _ (pocket/invalidate! #'slow-computation 60)
      _ (pocket/clear-mem-cache!)

      ;; Step 6: Recompute (miss everywhere)
      _ @(pocket/cached #'slow-computation 60)
      count-step-6 @computation-count]
  {:count-step-1 count-step-1
   :elapsed-step-2 elapsed-2
   :count-step-2 count-step-2
   :count-step-3 count-step-3
   :count-step-4 count-step-4
   :count-step-6 count-step-6})

(kind/test-last
 [(fn [{:keys [count-step-1 elapsed-step-2 count-step-2
               count-step-3 count-step-4 count-step-6]}]
    (and (= 1 count-step-1)
         (< elapsed-step-2 50)
         (= 1 count-step-2)
         (= 3 count-step-3)
         (= 3 count-step-4)
         (= 4 count-step-6)))])

;; ### Scenario 8: Synchronized Start (Barrier)

;; A stricter variant of Scenario 1 that uses a `CyclicBarrier` to
;; guarantee all threads enter `deref` at the same instant.
;; This is the direct contrast to the core.cache demonstration above.

(fresh-scenario!)

(let [barrier (java.util.concurrent.CyclicBarrier. 5)
      futures (doall (for [_ (range 5)]
                       (future
                         (.await barrier)
                         @(pocket/cached #'slow-computation 70))))
      results (mapv deref futures)]
  {:results results
   :computation-count @computation-count})

(kind/test-last
 [(fn [{:keys [results computation-count]}]
    (and (every? #(= 4900 %) results)
         (= 1 computation-count)))])

;; ---

;; ### Scenario 9: Concurrent Pipeline Deref

;; A pipeline where step 2 takes a `Cached` step 1 result as an argument.
;; Multiple threads deref step 2 concurrently — both steps should
;; compute exactly once.

(def step-a-count (atom 0))
(def step-b-count (atom 0))

(defn pipeline-step-a
  "First pipeline step: slow transform."
  [x]
  (swap! step-a-count inc)
  (Thread/sleep 200)
  (* x 10))

(defn pipeline-step-b
  "Second pipeline step: depends on step-a result."
  [data]
  (swap! step-b-count inc)
  (Thread/sleep 200)
  (+ data 1))

(do
  (reset! step-a-count 0)
  (reset! step-b-count 0)
  (pocket/cleanup!)
  (pocket/set-mem-cache-options! {:policy :lru :threshold 10})
  :ready)

;; Build a two-step pipeline, then deref from 5 threads:

(let [cached-a (pocket/cached #'pipeline-step-a 7)
      cached-b (pocket/cached #'pipeline-step-b cached-a)
      barrier (java.util.concurrent.CyclicBarrier. 5)
      futures (doall (for [_ (range 5)]
                       (future
                         (.await barrier)
                         @cached-b)))
      results (mapv deref futures)]
  {:results results
   :step-a-count @step-a-count
   :step-b-count @step-b-count})

(kind/test-last
 [(fn [{:keys [results step-a-count step-b-count]}]
    (and (every? #(= 71 %) results)
         (= 1 step-a-count)
         (= 1 step-b-count)))])

;; ---

;; ### Scenario 10: Concurrent Failure

;; Multiple threads hit a computation that throws.
;; All threads should see the exception.
;; A subsequent attempt should succeed (fresh delay).

(def concurrent-fail-count (atom 0))

(defn fail-once-computation
  "Fails when concurrent-fail-count is 0, succeeds after."
  [x]
  (let [n (swap! concurrent-fail-count inc)]
    (when (= 1 n)
      (Thread/sleep 200)
      (throw (ex-info "Transient error" {:x x})))
    (Thread/sleep 100)
    (* x x)))

(do
  (reset! concurrent-fail-count 0)
  (pocket/cleanup!)
  (pocket/set-mem-cache-options! {:policy :lru :threshold 10})
  :ready)

;; 5 threads hit the failing computation simultaneously:

(let [barrier (java.util.concurrent.CyclicBarrier. 5)
      futures (doall (for [_ (range 5)]
                       (future
                         (.await barrier)
                         (try
                           {:value @(pocket/cached #'fail-once-computation 8)}
                           (catch Exception e
                             {:error (.getMessage e)})))))
      first-results (mapv deref futures)
      errors (filterv :error first-results)
      successes (filterv :value first-results)]
  {:first-round-errors (count errors)
   :first-round-successes (count successes)
   :retry @(pocket/cached #'fail-once-computation 8)
   :total-calls @concurrent-fail-count})

(kind/test-last
 [(fn [{:keys [first-round-errors first-round-successes retry total-calls]}]
    (and (>= first-round-errors 1)
         (= 5 (+ first-round-errors first-round-successes))
         (= 64 retry)
         (>= total-calls 2)))])

;; ---

;; ### Scenario 11: Eviction Under Contention

;; With a very small cache (threshold=2) and many concurrent requests,
;; memory eviction happens frequently. The `in-flight` map still prevents
;; duplicate computation for the same key.

(fresh-scenario! {:mem-cache-opts {:policy :lru :threshold 2}})

;; Launch 4 threads per key, for 3 different keys.
;; Each key should compute exactly once despite eviction pressure.

(let [barrier (java.util.concurrent.CyclicBarrier. 12)
      futures (doall
               (for [x [80 81 82]
                     _ (range 4)]
                 (future
                   (.await barrier)
                   @(pocket/cached #'slow-computation x))))
      results (mapv deref futures)]
  {:results results
   :computation-count @computation-count
   :expected-results (vec (for [x [80 81 82]
                                _ (range 4)]
                            (* x x)))})

(kind/test-last
 [(fn [{:keys [results computation-count expected-results]}]
    (and (= expected-results results)
         (= 3 computation-count)))])

;; ---

;; ### Scenario 12: Rapid Deref After Invalidation

;; Invalidate a cached value and immediately re-request from multiple threads.
;; The re-request should compute exactly once.

(fresh-scenario!)

;; Compute and cache a value:

(let [_ @(pocket/cached #'slow-computation 90)
      count-after-first @computation-count
      ;; Invalidate
      _ (pocket/invalidate! #'slow-computation 90)
      ;; Immediately re-request from 5 concurrent threads
      barrier (java.util.concurrent.CyclicBarrier. 5)
      futures (doall (for [_ (range 5)]
                       (future
                         (.await barrier)
                         @(pocket/cached #'slow-computation 90))))
      results (mapv deref futures)
      count-after-retry @computation-count]
  {:results results
   :count-after-first count-after-first
   :count-after-retry count-after-retry})

(kind/test-last
 [(fn [{:keys [results count-after-first count-after-retry]}]
    (and (every? #(= 8100 %) results)
         (= 1 count-after-first)
         (= 2 count-after-retry)))])

;; ---

;; ## Design Notes

;; ### Why Not Use Caffeine?

;; [Caffeine](https://github.com/ben-manes/caffeine) (via
;; [Cloffeine](https://github.com/AppsFlyer/cloffeine)) provides
;; `LoadingCache` with built-in `computeIfAbsent` synchronization.
;; This would eliminate the need for our explicit `in-flight` map.

;; We chose `core.cache` because:
;; - Lighter dependency (pure Clojure data structures)
;; - Pluggable, immutable cache implementations
;; - Familiar to the Clojure ecosystem

;; Trade-off: We need the explicit `ConcurrentHashMap` synchronization layer.

;; ### The `computeIfAbsent` Contract

;; From the [Java documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html#computeIfAbsent-K-java.util.function.Function-):

;; > The mapping function should not modify this map during computation.

;; Our implementation is safe: the mapping function only creates a `delay`
;; (cheap, instantaneous). The actual computation happens when the delay
;; is dereferenced, **outside** of `computeIfAbsent`.

;; ## Cleanup

(pocket/cleanup!)
(pocket/reset-mem-cache-options!)
