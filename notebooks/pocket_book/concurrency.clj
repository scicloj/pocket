;; # Concurrency

(ns pocket-book.concurrency
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]))

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

;; ## Why `core.cache` Alone Is Insufficient

;; Clojure's [core.cache](https://github.com/clojure/core.cache) provides
;; `lookup-or-miss` which uses a delay-in-atom pattern:

;; ```clojure
;; (let [d (delay (miss-fn key))]    ; delay created BEFORE swap!
;;   (swap! cache-atom ...)
;;   @(lookup ...))
;; ```

;; The problem: **the delay is created per call, not per key**.
;; Each thread creates its own delay instance before racing into `swap!`.

;; This is a [known issue](https://clojure.atlassian.net/browse/CMEMOIZE-15)
;; (CMEMOIZE-15) — if cache eviction occurs between `swap!` completing and
;; `lookup` returning, threads can end up dereferencing different delays.

;; ## Pocket's Solution

;; Pocket adds a `ConcurrentHashMap` layer to synchronize in-flight computations:

;; ```clojure
;; (def ^ConcurrentHashMap in-flight
;;   (java.util.concurrent.ConcurrentHashMap.))
;;
;; ;; Inside the miss-fn:
;; (let [d (.computeIfAbsent
;;           in-flight path
;;           (fn [_]
;;             (delay
;;               (try
;;                 ;; disk check + computation here
;;                 (finally
;;                   (.remove in-flight path))))))]
;;   @d)
;; ```
;;
;; `computeIfAbsent` guarantees that only **one thread** creates the delay
;; for a given key. All concurrent threads receive the same delay instance.

;; ### The Key Principle

;; > **The delay is cheap; the computation is not.**

;; Creating a `delay` object is nearly instantaneous. The expensive work
;; happens when the delay is dereferenced. By ensuring all threads share
;; the same delay, we guarantee the computation runs exactly once.

;; ### Failure Handling

;; The `finally` block removes the entry from `in-flight` after computation
;; (success or failure). This provides **retry-on-failure** semantics:
;; if a computation throws, the next caller will get a fresh attempt
;; rather than a cached exception.
;;
;; This matches the behavior of
;; [core.memoize's RetryingDelay](https://github.com/clojure/core.memoize).

;; ## Architecture Layers

^:kindly/hide-code
(kind/mermaid
 "flowchart TB
    subgraph Request
    D[deref Cached]
    end
    subgraph Synchronization
    CHM[ConcurrentHashMap\\nin-flight]
    DEL[delay]
    end
    subgraph Caching
    MEM[Memory Cache\\ncore.cache]
    DISK[Disk Cache\\nNippy files]
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
      _ (scicloj.pocket.impl.cache/clear-mem-cache!)
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
      _ (scicloj.pocket.impl.cache/clear-mem-cache!)
      
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
    (and (= 1 count-step-1)            ; step 1: computed once
         (< elapsed-step-2 50)          ; step 2: instant (memory hit)
         (= 1 count-step-2)             ; step 2: no recompute
         (= 3 count-step-3)             ; step 3: computed 61, 62
         (= 3 count-step-4)             ; step 4: disk hit (no recompute)
         (= 4 count-step-6)))])         ; step 6: recomputed after invalidation

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
