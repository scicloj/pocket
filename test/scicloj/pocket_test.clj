(ns scicloj.pocket-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [scicloj.pocket :as pocket]
            [scicloj.pocket.impl.cache :as impl]
            [babashka.fs :as fs]))

(def test-cache-dir "/tmp/pocket-test-cache")

(defn cleanup-cache [f]
  (fs/delete-tree test-cache-dir)
  (impl/clear-mem-cache!)
  (binding [pocket/*base-cache-dir* test-cache-dir]
    (f))
  (impl/clear-mem-cache!)
  (fs/delete-tree test-cache-dir))

(use-fixtures :each cleanup-cache)

(defn expensive-add [x y]
  (+ x y))

(defn returns-nil []
  nil)

(deftest test-basic-caching
  (testing "Basic cached computation"
    (let [result (pocket/cached #'expensive-add 10 20)]
      (is (= 30 @result))
      ;; Second deref should load from cache
      (is (= 30 @result))))

  (testing "Different args create different cache keys"
    (let [result1 @(pocket/cached #'expensive-add 1 2)
          result2 @(pocket/cached #'expensive-add 3 4)]
      (is (= 3 result1))
      (is (= 7 result2)))))

(deftest test-nil-handling
  (testing "Nil values can be cached"
    (let [result (pocket/cached #'returns-nil)]
      (is (nil? @result))
      ;; Should load nil from cache
      (is (nil? @result)))))

(deftest test-cached-fn
  (testing "cached-fn wrapper"
    (let [cached-add (pocket/cached-fn #'expensive-add)]
      (is (= 30 @(cached-add 10 20)))
      (is (= 50 @(cached-add 20 30))))))

(deftest test-maybe-deref
  (testing "maybe-deref handles both IDeref and regular values"
    (is (= 42 (pocket/maybe-deref 42)))
    (is (= 42 (pocket/maybe-deref (delay 42))))))

(deftest test-protocol-nil
  (testing "PIdentifiable protocol handles nil"
    (is (nil? (pocket/->id nil)))
    ;; Should not throw IllegalArgumentException
    (is (nil? @(pocket/cached #'returns-nil)))))

(deftest test-thread-safety
  (testing "Concurrent derefs compute only once"
    (let [call-count (atom 0)
          slow-fn (fn [x]
                    (swap! call-count inc)
                    (Thread/sleep 200)
                    (* x 10))]
      (with-redefs [expensive-add slow-fn]
        (let [cached-val (pocket/cached #'expensive-add 5)
              futures (doall (repeatedly 20 #(future @cached-val)))
              results (mapv deref futures)]
          (is (every? #(= 50 %) results))
          (is (= 1 @call-count)))))))

(deftest test-in-memory-cache
  (testing "Second deref comes from memory, not disk"
    (let [result (pocket/cached #'expensive-add 10 20)]
      ;; First deref populates both disk and memory
      (is (= 30 @result))
      ;; Delete the disk cache
      (fs/delete-tree test-cache-dir)
      ;; Second deref should still work (from memory)
      (is (= 30 @result)))))

(deftest test-lru-eviction
  (testing "LRU eviction falls back to disk"
    (pocket/set-mem-cache-options! {:policy :lru :threshold 2})
    ;; Cache 3 values — first should be evicted from memory
    (is (= 3 @(pocket/cached #'expensive-add 1 2)))
    (is (= 7 @(pocket/cached #'expensive-add 3 4)))
    (is (= 11 @(pocket/cached #'expensive-add 5 6)))
    ;; First value evicted from memory, but still on disk
    (is (= 3 @(pocket/cached #'expensive-add 1 2)))
    ;; Reset to default
    (pocket/set-mem-cache-options! {:policy :lru})))

(deftest test-cache-policies
  (testing "FIFO policy works"
    (pocket/set-mem-cache-options! {:policy :fifo :threshold 10})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "LU policy works"
    (pocket/set-mem-cache-options! {:policy :lu :threshold 10})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "TTL policy works"
    (pocket/set-mem-cache-options! {:policy :ttl :ttl 60000})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "Basic policy works"
    (pocket/set-mem-cache-options! {:policy :basic})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "LIRS policy works"
    (pocket/set-mem-cache-options! {:policy :lirs :s-history-limit 10 :q-history-limit 5})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "Soft policy works"
    (pocket/set-mem-cache-options! {:policy :soft})
    (is (= 30 @(pocket/cached #'expensive-add 10 20))))

  (testing "Unknown policy throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pocket/set-mem-cache-options! {:policy :unknown}))))

  ;; Reset to default
  (pocket/set-mem-cache-options! {:policy :lru}))

(deftest test-ttl-expiry
  (testing "TTL entry expires and recomputes from disk"
    (pocket/set-mem-cache-options! {:policy :ttl :ttl 100})
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      (with-redefs [expensive-add counting-fn]
        ;; First deref: computes and caches
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))
        ;; Wait for TTL to expire
        (Thread/sleep 200)
        ;; Second deref: expired from memory, loads from disk (no recomputation)
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))))
    ;; Reset to default
    (pocket/set-mem-cache-options! {:policy :lru})))

(deftest test-cleanup!
  (testing "cleanup! clears disk and memory, returns correct map"
    ;; Populate cache
    (is (= 30 @(pocket/cached #'expensive-add 10 20)))
    (is (fs/exists? test-cache-dir))
    ;; Cleanup
    (let [result (pocket/cleanup!)]
      (is (= test-cache-dir (:dir result)))
      (is (true? (:existed result)))
      (is (not (fs/exists? test-cache-dir)))))

  (testing "cleanup! when no cache exists"
    (let [result (pocket/cleanup!)]
      (is (false? (:existed result)))))

  (testing "cleanup! clears in-memory cache"
    ;; Populate cache
    (is (= 30 @(pocket/cached #'expensive-add 10 20)))
    ;; Cleanup (removes disk and memory)
    (pocket/cleanup!)
    ;; Verify computation runs again (not served from memory)
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))))))

(deftest test-recursive-pipeline
  (testing "Cached value as arg to another cached fn"
    (let [step1-count (atom 0)
          step2-count (atom 0)]
      (defn pipeline-step1 [x]
        (swap! step1-count inc)
        (* x 10))
      (defn pipeline-step2 [data]
        (swap! step2-count inc)
        (+ (pocket/maybe-deref data) 1))
      ;; Build pipeline
      (let [cached-step1 (pocket/cached #'pipeline-step1 5)
            cached-step2 (pocket/cached #'pipeline-step2 cached-step1)]
        ;; First run: both steps compute
        (is (= 51 @cached-step2))
        (is (= 1 @step1-count))
        (is (= 1 @step2-count))
        ;; Second run with same pipeline: all from cache
        (let [cached-step1b (pocket/cached #'pipeline-step1 5)
              cached-step2b (pocket/cached #'pipeline-step2 cached-step1b)]
          (is (= 51 @cached-step2b))
          (is (= 1 @step1-count))
          (is (= 1 @step2-count)))))))

(deftest test-set-base-cache-dir!
  (testing "set-base-cache-dir! changes cache location"
    (let [alt-dir "/tmp/pocket-test-alt"]
      (try
        ;; Override the thread-local binding from the fixture
        (set! pocket/*base-cache-dir* alt-dir)
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (fs/exists? alt-dir))
        (is (not (fs/exists? test-cache-dir)))
        (finally
          (fs/delete-tree alt-dir)
          (set! pocket/*base-cache-dir* test-cache-dir))))))

(deftest test-identity
  (testing "Var identity is its name"
    (is (= "expensive-add" (pocket/->id #'expensive-add))))

  (testing "Map identity has sorted keys"
    (is (= (pocket/->id {:b 2 :a 1})
           (pocket/->id {:a 1 :b 2}))))

  (testing "Cached identity captures computation graph"
    (let [c (pocket/cached #'expensive-add 1 2)]
      (is (= (list "expensive-add" [1 2])
             (pocket/->id c)))))

  (testing "Nil identity"
    (is (nil? (pocket/->id nil))))

  (testing "MapEntry identity"
    (is (string? (pocket/->id (first {:a 1}))))))

(deftest test-invalidate!
  (testing "Invalidate a specific cached computation"
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      (with-redefs [expensive-add counting-fn]
        ;; Cache a value
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))
        ;; Invalidate it
        (let [result (pocket/invalidate! #'expensive-add 10 20)]
          (is (true? (:existed result))))
        ;; Next deref should recompute
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 2 @call-count)))))

  (testing "Invalidate non-existent entry"
    (let [result (pocket/invalidate! #'expensive-add 999 999)]
      (is (false? (:existed result))))))

(deftest test-invalidate-fn!
  (testing "Invalidate all entries for a function"
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      ;; Cache several entries
      (is (= 3 @(pocket/cached #'expensive-add 1 2)))
      (is (= 7 @(pocket/cached #'expensive-add 3 4)))
      (is (= 11 @(pocket/cached #'expensive-add 5 6)))
      ;; Invalidate all
      (let [result (pocket/invalidate-fn! #'expensive-add)]
        (is (= "expensive-add" (:fn-name result)))
        (is (= 3 (:count result))))
      ;; All should recompute
      (with-redefs [expensive-add counting-fn]
        (is (= 3 @(pocket/cached #'expensive-add 1 2)))
        (is (= 7 @(pocket/cached #'expensive-add 3 4)))
        (is (= 2 @call-count)))
      ;; Clean up for next test
      (pocket/invalidate-fn! #'expensive-add)))

  (testing "Invalidate-fn with no cached entries"
    (let [result (pocket/invalidate-fn! #'expensive-add)]
      (is (= 0 (:count result))))))
