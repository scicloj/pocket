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

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Thread A                    Thread B
────────                    ────────
check cache → miss          check cache → miss
compute value               compute value     ← duplicate!
store result                store result"])

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

^:kindly/hide-code
(kind/code
 "(def ^ConcurrentHashMap in-flight
  (java.util.concurrent.ConcurrentHashMap.))

;; Inside the miss-fn:
(let [d (.computeIfAbsent
          in-flight path
          (fn [_]
            (delay
              (try
                ;; disk check + computation here
                (finally
                  (.remove in-flight path))))))]
  @d)")

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
(kind/hiccup
 [:table {:style "border-collapse: collapse; width: 100%;"}
  [:thead
   [:tr
    [:th {:style "border: 1px solid #ddd; padding: 8px; text-align: left;"} "Layer"]
    [:th {:style "border: 1px solid #ddd; padding: 8px; text-align: left;"} "Purpose"]
    [:th {:style "border: 1px solid #ddd; padding: 8px; text-align: left;"} "Guarantee"]]]
  [:tbody
   [:tr
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "ConcurrentHashMap"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Delay creation"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "One delay per key"]]
   [:tr
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "delay"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Computation"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "One execution per delay"]]
   [:tr
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "core.cache (mem-cache)"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "In-memory caching"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Fast repeated access"]]
   [:tr
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Disk cache"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Persistence"]
    [:td {:style "border: 1px solid #ddd; padding: 8px;"} "Cross-session caching"]]]])

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

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Timeline (ms):   0         100        300        400
                  │          │          │          │
Thread A:        [─── request ───][─── computing ───]──→ result
Thread B:                   [─── request ───][ wait ]──→ result
                                              ↑
                              B waits for A's computation"])

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
    (and (every? #(= 100 %) results)
         (= 1 computation-count)))])

;; ---

;; ### Scenario 2: Memory Cache Hit

;; After the first computation, subsequent requests hit the memory cache
;; instantly (no disk I/O, no recomputation).

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Timeline:
  Thread A:  [── computing ──]
                            ↓
                       mem-cache populated
  Thread B:                          [request]──→ instant result
                                        ↑
                                  memory cache hit"])

(fresh-scenario!)

;; First request computes:
@(pocket/cached #'slow-computation 20)

;; Second request should be instant (memory hit):
(let [start (System/currentTimeMillis)
      result @(pocket/cached #'slow-computation 20)
      elapsed (- (System/currentTimeMillis) start)]
  {:result result
   :elapsed-ms elapsed
   :computation-count @computation-count})

(kind/test-last
 [(fn [{:keys [result elapsed-ms computation-count]}]
    (and (= 400 result)
         (< elapsed-ms 50)
         (= 1 computation-count)))])

;; ---

;; ### Scenario 3: Disk Cache Hit After Memory Eviction

;; Fill the memory cache to evict our entry, then verify
;; the next request reads from disk (not recomputes).

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Timeline:
  1. Compute value for arg=30          → stored in mem + disk
  2. Compute 3 more values (31,32,33)  → arg=30 evicted from mem (LRU)
  3. Request arg=30 again              → disk cache hit (no recompute)"])

(fresh-scenario!)

;; Step 1: Compute initial value
@(pocket/cached #'slow-computation 30)

;; Step 2: Fill cache to evict arg=30 from memory
@(pocket/cached #'slow-computation 31)
@(pocket/cached #'slow-computation 32)
@(pocket/cached #'slow-computation 33)

(let [count-before @computation-count]
  ;; Step 3: Request arg=30 again
  (let [result @(pocket/cached #'slow-computation 30)
        count-after @computation-count]
    {:result result
     :recomputed? (not= count-before count-after)
     :total-computations count-after}))

(kind/test-last
 [(fn [{:keys [result recomputed? total-computations]}]
    (and (= 900 result)
         (not recomputed?)
         (= 4 total-computations)))])

;; ---

;; ### Scenario 4: Failure and Retry

;; When a computation fails, the exception is not cached.
;; The next caller gets a fresh attempt.

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Timeline:
  1. Thread A requests → computation fails (exception)
  2. Thread B requests → fresh computation succeeds
  3. Thread C requests → cache hit (no recompute)"])

(def failure-count (atom 0))

(defn flaky-computation
  "Fails on first call, succeeds thereafter."
  [x]
  (if (zero? @failure-count)
    (do (swap! failure-count inc)
        (throw (ex-info "Temporary failure" {:x x})))
    (do (swap! failure-count inc)
        (* x 100))))

(reset! failure-count 0)
(pocket/cleanup!)

;; First attempt fails:
(let [attempt-1 (try
                  @(pocket/cached #'flaky-computation 5)
                  (catch Exception e {:error (.getMessage e)}))]
  attempt-1)

(kind/test-last [(fn [r] (contains? r :error))])

;; Second attempt succeeds (retries, not cached exception):
@(pocket/cached #'flaky-computation 5)

(kind/test-last [(fn [r] (= 500 r))])

;; Third attempt hits cache:
(let [count-before @failure-count
      result @(pocket/cached #'flaky-computation 5)
      count-after @failure-count]
  {:result result
   :attempts-unchanged? (= count-before count-after)})

(kind/test-last
 [(fn [{:keys [result attempts-unchanged?]}]
    (and (= 500 result) attempts-unchanged?))])

;; ---

;; ### Scenario 5: Different Arguments Compute in Parallel

;; Requests with different arguments run in parallel
;; (no unnecessary serialization).

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Timeline (ms):   0                   300
                  │                    │
Thread A (x=40): [──── computing ────]──→ 1600
Thread B (x=41): [──── computing ────]──→ 1681
Thread C (x=42): [──── computing ────]──→ 1764
                  ↑
            All start ~simultaneously, run in parallel"])

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
 [(fn [{:keys [results parallel?]}]
    (and (= [1600 1681 1764] results)
         parallel?))])

;; ---

;; ### Scenario 6: Disk Hit with Empty Memory Cache

;; Clear memory cache while keeping disk cache.
;; All requests should read from disk without recomputing.

^:kindly/hide-code
(kind/hiccup
 [:pre
  "Setup: value for arg=50 is on disk but NOT in memory

  Threads A, B, C all request x=50
  → All read from disk (no computation)"])

(fresh-scenario!)

;; Compute and cache arg=50
@(pocket/cached #'slow-computation 50)

;; Clear only memory cache (disk remains)
(scicloj.pocket.impl.cache/clear-mem-cache!)

(let [count-before @computation-count
      ;; Multiple threads hit disk cache
      futures (mapv (fn [_] (future @(pocket/cached #'slow-computation 50)))
                    (range 3))
      results (mapv deref futures)
      count-after @computation-count]
  {:results results
   :recomputed? (not= count-before count-after)})

(kind/test-last
 [(fn [{:keys [results recomputed?]}]
    (and (every? #(= 2500 %) results)
         (not recomputed?)))])

;; ---

;; ### Scenario 7: Full Cache Hierarchy Test

;; A comprehensive scenario testing all cache layers:
;; 1. Miss everywhere → compute
;; 2. Memory hit → instant
;; 3. Memory evicted, disk hit → read from disk
;; 4. Disk deleted → recompute

^:kindly/hide-code
(kind/hiccup
 [:pre
  "┌─────────────────────────────────────────────────────────────┐
  │ Step 1: Request x=60         → COMPUTE (miss everywhere)    │
  │ Step 2: Request x=60 again   → MEMORY HIT (instant)         │
  │ Step 3: Evict from memory    → (fill cache with other vals) │
  │ Step 4: Request x=60         → DISK HIT (read from disk)    │
  │ Step 5: Delete disk cache    → invalidate!                  │
  │ Step 6: Request x=60         → COMPUTE (miss everywhere)    │
  └─────────────────────────────────────────────────────────────┘"])

(fresh-scenario! {:mem-cache-opts {:policy :lru :threshold 2}})

;; Step 1: Initial computation
@(pocket/cached #'slow-computation 60)
(def after-step-1 @computation-count)

;; Step 2: Memory hit
(let [start (System/currentTimeMillis)]
  @(pocket/cached #'slow-computation 60)
  {:step-2-fast? (< (- (System/currentTimeMillis) start) 50)
   :after-step-2 @computation-count})

(kind/test-last
 [(fn [{:keys [step-2-fast? after-step-2]}]
    (and step-2-fast? (= 1 after-step-2)))])

;; Step 3: Evict from memory
@(pocket/cached #'slow-computation 61)
@(pocket/cached #'slow-computation 62)

;; Step 4: Disk hit (memory miss)
(let [count-before @computation-count]
  @(pocket/cached #'slow-computation 60)
  {:step-4-no-recompute? (= count-before @computation-count)})

(kind/test-last
 [(fn [{:keys [step-4-no-recompute?]}] step-4-no-recompute?)])

;; Step 5: Delete disk cache
(pocket/invalidate! #'slow-computation 60)
(scicloj.pocket.impl.cache/clear-mem-cache!)

;; Step 6: Recompute (miss everywhere)
(let [count-before @computation-count]
  @(pocket/cached #'slow-computation 60)
  {:step-6-recomputed? (= (inc count-before) @computation-count)
   :total-computations @computation-count})

(kind/test-last
 [(fn [{:keys [step-6-recomputed? total-computations]}]
    (and step-6-recomputed? (= 4 total-computations)))])

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
