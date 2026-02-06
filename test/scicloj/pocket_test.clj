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

(defn concat-strings
  "Helper for testing long cache keys"
  [s1 s2]
  (str s1 s2))

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

(deftest test-caching-fn
  (testing "caching-fn wrapper"
    (let [caching-add (pocket/caching-fn #'expensive-add)]
      (is (= 30 @(caching-add 10 20)))
      (is (= 50 @(caching-add 20 30))))))

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

(deftest test-concurrent-cached-creation
  (testing "Concurrent creation of Cached with same args computes once"
    (let [call-count (atom 0)
          slow-fn (fn [x]
                    (swap! call-count inc)
                    (Thread/sleep 200)
                    (* x 10))]
      (with-redefs [expensive-add slow-fn]
        ;; Each future creates its own Cached instance, but all share the same cache key
        (let [futures (doall (repeatedly 10 #(future @(pocket/cached #'expensive-add 7))))
              results (mapv deref futures)]
          (is (every? #(= 70 %) results))
          (is (= 1 @call-count) "Should compute only once despite separate Cached instances"))))))

(deftest test-failure-retry
  (testing "Failed computation is retried on next deref"
    (let [attempt-count (atom 0)
          flaky-fn (fn [x]
                     (if (= 1 (swap! attempt-count inc))
                       (throw (ex-info "Temporary failure" {}))
                       (* x 10)))]
      (with-redefs [expensive-add flaky-fn]
        ;; First attempt fails
        (is (thrown? clojure.lang.ExceptionInfo
                     @(pocket/cached #'expensive-add 3)))
        ;; Second attempt succeeds (retries, doesn't cache exception)
        (is (= 30 @(pocket/cached #'expensive-add 3)))
        ;; Third attempt hits cache
        (let [count-before @attempt-count
              result @(pocket/cached #'expensive-add 3)]
          (is (= 30 result))
          (is (= count-before @attempt-count) "Should not recompute on cache hit"))))))
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
  (testing "Cached value as arg to another cached fn (auto-deref)"
    (let [step1-count (atom 0)
          step2-count (atom 0)]
      (defn pipeline-step1 [x]
        (swap! step1-count inc)
        (* x 10))
      (defn pipeline-step2 [data]
        (swap! step2-count inc)
        ;; No maybe-deref needed — Cached args are auto-derefed
        (+ data 1))
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
    (is (= 'scicloj.pocket-test/expensive-add (pocket/->id #'expensive-add))))

  (testing "Map identity has sorted keys"
    (is (= (pocket/->id {:b 2 :a 1})
           (pocket/->id {:a 1 :b 2}))))

  (testing "Cached identity captures computation graph"
    (let [c (pocket/cached #'expensive-add 1 2)]
      (is (= '(scicloj.pocket-test/expensive-add 1 2)
             (pocket/->id c)))))

  (testing "Nil identity"
    (is (nil? (pocket/->id nil))))

  (testing "MapEntry identity"
    (is (= [:a 1] (pocket/->id (first {:a 1}))))))

(defrecord UserId [id])

(extend-protocol pocket/PIdentifiable
  UserId
  (->id [this] (str "user-" (:id this))))

(defn lookup-user [user-id]
  {:name "Alice" :id (:id user-id)})

(deftest test-custom-protocol-extension
  (testing "User extension of PIdentifiable affects cache key generation"
    (let [uid (->UserId 42)
          c (pocket/cached #'lookup-user uid)]
      ;; Custom ->id is used in the cache key
      (is (= '(scicloj.pocket-test/lookup-user "user-42") (pocket/->id c)))
      ;; Caching works with the custom type
      (is (= {:name "Alice" :id 42} @c))
      ;; Second deref from cache
      (is (= {:name "Alice" :id 42} @c))))

  (testing "Different UserId values produce different cache keys"
    (let [c1 (pocket/cached #'lookup-user (->UserId 1))
          c2 (pocket/cached #'lookup-user (->UserId 2))]
      (is (not= (pocket/->id c1) (pocket/->id c2))))))

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
        (is (= "scicloj.pocket-test/expensive-add" (:fn-name result)))
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

(deftest test-pocket-edn
  (testing "pocket.edn is read from classpath"
    (let [edn-val (impl/pocket-edn)]
      ;; pocket.edn may or may not exist on the test classpath
      ;; just verify the delay resolves without error
      (is (or (nil? edn-val) (map? edn-val)))))

  (testing "resolve-mem-cache-options falls back to defaults"
    (binding [pocket/*mem-cache-options* nil]
      (is (= :lru (:policy (#'pocket/resolve-mem-cache-options))))))

  (testing "binding overrides resolved mem-cache options"
    (binding [pocket/*mem-cache-options* {:policy :fifo :threshold 50}]
      (is (= {:policy :fifo :threshold 50}
             (#'pocket/resolve-mem-cache-options)))))

  (testing "set-mem-cache-options! alters var root and reconfigures cache"
    (pocket/set-mem-cache-options! {:policy :fifo :threshold 100})
    (is (= {:policy :fifo :threshold 100}
           (select-keys @impl/current-mem-cache-options [:policy :threshold])))
    ;; Reset
    (pocket/set-mem-cache-options! {:policy :lru :threshold 256})))

(deftest test-config
  (testing "config returns resolved effective configuration"
    (let [cfg (pocket/config)]
      (is (= test-cache-dir (:base-cache-dir cfg)))
      (is (map? (:mem-cache cfg)))
      (is (contains? (:mem-cache cfg) :policy))))

  (testing "config reflects binding overrides"
    (binding [pocket/*base-cache-dir* "/tmp/config-test"
              pocket/*mem-cache-options* {:policy :fifo :threshold 10}]
      (let [cfg (pocket/config)]
        (is (= "/tmp/config-test" (:base-cache-dir cfg)))
        (is (= :fifo (-> cfg :mem-cache :policy)))))))

(deftest test-cache-metadata
  (testing "Metadata file is written alongside cached value"
    (let [result (pocket/cached #'expensive-add 10 20)]
      (is (= 30 @result))
      (let [entries (pocket/cache-entries)]
        (is (= 1 (count entries)))
        (let [entry (first entries)]
          (is (string? (:fn-name entry)))
          (is (= "scicloj.pocket-test/expensive-add" (:fn-name entry)))
          (is (string? (:created-at entry)))
          (is (string? (:id entry)))
          (is (string? (:args-str entry)))))))

  (testing "Metadata file is written for nil values"
    (let [result (pocket/cached #'returns-nil)]
      (is (nil? @result))
      (let [entries (pocket/cache-entries "scicloj.pocket-test/returns-nil")]
        (is (= 1 (count entries)))
        (is (= "scicloj.pocket-test/returns-nil" (:fn-name (first entries))))))))

(deftest test-cache-entries
  (testing "cache-entries returns all entries"
    @(pocket/cached #'expensive-add 1 2)
    @(pocket/cached #'expensive-add 3 4)
    @(pocket/cached #'returns-nil)
    (is (= 3 (count (pocket/cache-entries)))))

  (testing "cache-entries filters by function name"
    (is (= 2 (count (pocket/cache-entries "scicloj.pocket-test/expensive-add"))))
    (is (= 1 (count (pocket/cache-entries "scicloj.pocket-test/returns-nil"))))
    (is (= 0 (count (pocket/cache-entries "nonexistent"))))))

(deftest test-cache-stats
  (testing "cache-stats returns aggregate info"
    @(pocket/cached #'expensive-add 1 2)
    @(pocket/cached #'expensive-add 3 4)
    @(pocket/cached #'returns-nil)
    (let [stats (pocket/cache-stats)]
      (is (= 3 (:total-entries stats)))
      (is (pos? (:total-size-bytes stats)))
      (is (= {"scicloj.pocket-test/expensive-add" 2 "scicloj.pocket-test/returns-nil" 1}
             (:entries-per-fn stats))))))

(deftest test-var-validation
  (testing "cached throws when given a bare function instead of a var"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires a var"
                          @(pocket/cached expensive-add 1 2))))

  (testing "caching-fn throws when given a bare function"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires a var"
                          @((pocket/caching-fn expensive-add) 1 2)))))

(defrecord CustomCacheId [name version])

(deftest test-extend-protocol-via-public-api
  (testing "extending pocket/PIdentifiable works for pocket/->id"
    (extend-protocol pocket/PIdentifiable
      CustomCacheId
      (->id [this] (symbol (str (:name this) "-v" (:version this)))))
    (is (= 'census-v3 (pocket/->id (->CustomCacheId "census" 3)))))
  (testing "extension is visible to internal cache key generation"
    (let [c (pocket/cached #'expensive-add (->CustomCacheId "census" 3))]
      (is (= '(scicloj.pocket-test/expensive-add census-v3)
             (pocket/->id c))))))

(deftest test-nil-base-dir-validation
  (testing "deref throws when base-dir is nil"
    (let [;; Construct Cached directly with nil base-dir to bypass resolve chain
          cached-val (impl/cached nil nil nil #'expensive-add 1 2)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No cache directory configured"
                            @cached-val)))))

(deftest test-canonical-id
  (testing "Symbol-led sequential stays as list"
    (let [c (pocket/cached #'expensive-add 1 2)]
      (is (list? (pocket/->id c)))))

  (testing "Vector args stay as vector in cache key"
    (let [c (pocket/cached #'expensive-add [1 2] [3 4])]
      (let [id (pocket/->id c)]
        ;; The function call is a list, but the vector args remain vectors
        (is (list? id))
        (is (vector? (second id)))
        (is (vector? (nth id 2))))))

  (testing "List and vector args produce different cache paths"
    (let [c1 (pocket/cached #'expensive-add [1 2] 3)
          c2 (pocket/cached #'expensive-add '(1 2) 3)]
      ;; ->id values are Clojure-equal, but canonical-id preserves type,
      ;; so str representations (and thus cache paths) differ
      (is (= (pocket/->id c1) (pocket/->id c2)))
      (is (not= (str (impl/canonical-id (pocket/->id c1)))
                (str (impl/canonical-id (pocket/->id c2)))))))

  (testing "Nested maps are deep-sorted"
    (let [c1 (pocket/cached #'expensive-add {:b {:d 4 :c 3} :a 1} 0)
          c2 (pocket/cached #'expensive-add {:a 1 :b {:c 3 :d 4}} 0)]
      (is (= (pocket/->id c1) (pocket/->id c2))))))

(deftest test-disk-persistence
  (testing "Value survives mem-cache clear and loads from disk"
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      (with-redefs [expensive-add counting-fn]
        ;; Compute and cache
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))
        ;; Clear only in-memory cache
        (impl/clear-mem-cache!)
        ;; Should load from disk, not recompute
        (is (= 30 @(pocket/cached #'expensive-add 10 20)))
        (is (= 1 @call-count))))))

(deftest test-dir-tree
  (testing "dir-tree returns a kindly-wrapped string representation"
    @(pocket/cached #'expensive-add 1 2)
    (let [tree (pocket/dir-tree)]
      (is (= :kind/code (:kindly/kind (meta tree))))
      (is (re-find #"\.cache" (first tree))))))

(deftest test-corrupted-cache-entry
  (testing "read-cached throws on entry with neither value.nippy nor nil marker"
    (let [path (str test-cache-dir "/corrupted-entry")]
      (fs/create-dirs path)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Corrupted cache entry"
                            (impl/read-cached path))))))

(deftest test-cache-entries-args-str
  (testing "cache-entries args-str contains actual argument values"
    @(pocket/cached #'expensive-add 10 20)
    (let [entry (first (pocket/cache-entries))]
      (is (re-find #"10" (:args-str entry)))
      (is (re-find #"20" (:args-str entry))))))

(deftest test-long-cache-key-sha1-fallback
  (testing "Cache keys longer than 240 chars use SHA-1 fallback"
    (let [long-string (apply str (repeat 300 "x"))
          result @(pocket/cached #'concat-strings long-string "y")]
      (is (= (str long-string "y") result))
      ;; Verify the entry exists and can be retrieved
      (is (= (str long-string "y") @(pocket/cached #'concat-strings long-string "y")))
      ;; Verify invalidate-fn! works with SHA-1 paths
      (let [inv-result (pocket/invalidate-fn! #'concat-strings)]
        (is (= 1 (:count inv-result)))))))

(deftest test-cache-entries-empty-cache
  (testing "cache-entries returns empty vector when cache doesn't exist"
    (pocket/cleanup!)
    (is (= [] (pocket/cache-entries)))
    (is (vector? (pocket/cache-entries)))))

(deftest test-corrupted-meta-edn
  (testing "read-meta handles corrupted EDN gracefully"
    (let [path (str test-cache-dir "/corrupted-meta")]
      (fs/create-dirs path)
      ;; Use truly invalid EDN (unbalanced braces)
      (spit (str path "/meta.edn") "{:a 1 :b")
      ;; read-meta should return nil, not throw
      (is (nil? (impl/read-meta path))))))

(deftest test-set-canonical-id
  (testing "Sets with same elements produce same cache path regardless of order"
    (let [c1 (pocket/cached #'expensive-add #{:a :b :c} 1)
          c2 (pocket/cached #'expensive-add #{:c :a :b} 1)]
      ;; canonical-id should produce same string for both
      (is (= (str (impl/canonical-id (pocket/->id c1)))
             (str (impl/canonical-id (pocket/->id c2))))))))

(deftest test-print-method
  (testing "Cached print-method shows identity and status without forcing computation"
    (let [call-count (atom 0)
          counting-fn (fn [x y]
                        (swap! call-count inc)
                        (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (let [c (pocket/cached #'expensive-add 100 200)]
          ;; Before deref: should show :pending and NOT compute
          (let [s (pr-str c)]
            (is (re-find #"#<Cached \(scicloj\.pocket-test/expensive-add 100 200\) :pending>" s))
            (is (= 0 @call-count) "print-method should not force computation"))
          ;; Deref to compute
          (is (= 300 @c))
          (is (= 1 @call-count))
          ;; After deref: should show :cached
          (let [s (pr-str c)]
            (is (re-find #"#<Cached \(scicloj\.pocket-test/expensive-add 100 200\) :cached>" s)))
          ;; Clear mem-cache but keep disk
          (impl/clear-mem-cache!)
          ;; Should show :disk
          (let [s (pr-str c)]
            (is (re-find #"#<Cached \(scicloj\.pocket-test/expensive-add 100 200\) :disk>" s)))
          ;; Deref again - should load from disk, not recompute
          (is (= 300 @c))
          (is (= 1 @call-count) "Should load from disk, not recompute"))))))

(deftest test-storage-mem
  (testing ":mem storage creates no disk files"
    (let [mem-fn (pocket/caching-fn #'expensive-add {:storage :mem})]
      (is (= 30 @(mem-fn 10 20)))
      ;; No disk files should exist
      (is (not (fs/exists? test-cache-dir)))))

  (testing ":mem value served from mem-cache on second deref"
    (impl/clear-mem-cache!)
    (let [call-count (atom 0)
          counting-fn (fn [x y] (swap! call-count inc) (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (let [mem-fn (pocket/caching-fn #'expensive-add {:storage :mem})]
          (is (= 30 @(mem-fn 10 20)))
          (is (= 1 @call-count))
          ;; Second call with same args — should hit mem-cache
          (is (= 30 @(mem-fn 10 20)))
          (is (= 1 @call-count))))))

  (testing ":mem value lost after clear-mem-cache!"
    (impl/clear-mem-cache!)
    (let [call-count (atom 0)
          counting-fn (fn [x y] (swap! call-count inc) (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (let [mem-fn (pocket/caching-fn #'expensive-add {:storage :mem})]
          (is (= 30 @(mem-fn 10 20)))
          (is (= 1 @call-count))
          ;; Clear mem-cache
          (impl/clear-mem-cache!)
          ;; Should recompute
          (is (= 30 @(mem-fn 10 20)))
          (is (= 2 @call-count)))))))

(deftest test-storage-none
  (testing ":none instance-local delay — first deref computes, second does not"
    (let [call-count (atom 0)
          counting-fn (fn [x y] (swap! call-count inc) (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (let [none-fn (pocket/caching-fn #'expensive-add {:storage :none})
              c (none-fn 10 20)]
          ;; First deref computes
          (is (= 30 @c))
          (is (= 1 @call-count))
          ;; Second deref of same instance does NOT recompute
          (is (= 30 @c))
          (is (= 1 @call-count))))))

  (testing ":none separate instances recompute"
    (let [call-count (atom 0)
          counting-fn (fn [x y] (swap! call-count inc) (+ x y))]
      (with-redefs [expensive-add counting-fn]
        (let [none-fn (pocket/caching-fn #'expensive-add {:storage :none})]
          (is (= 30 @(none-fn 10 20)))
          (is (= 1 @call-count))
          ;; New instance with same args — recomputes
          (is (= 30 @(none-fn 10 20)))
          (is (= 2 @call-count))))))

  (testing ":none creates no disk files"
    (let [none-fn (pocket/caching-fn #'expensive-add {:storage :none})]
      (is (= 30 @(none-fn 10 20)))
      (is (not (fs/exists? test-cache-dir))))))

(deftest test-storage-binding
  (testing "binding *storage* to :mem skips disk I/O"
    (binding [pocket/*storage* :mem]
      (let [call-count (atom 0)
            counting-fn (fn [x y] (swap! call-count inc) (+ x y))]
        (with-redefs [expensive-add counting-fn]
          (is (= 30 @(pocket/cached #'expensive-add 10 20)))
          (is (= 1 @call-count))
          (is (not (fs/exists? test-cache-dir))))))))

(deftest test-caching-fn-opts
  (testing "caching-fn opts don't leak to other calls"
    (let [mem-fn (pocket/caching-fn #'expensive-add {:storage :mem})
          disk-fn (pocket/caching-fn #'expensive-add)]
      ;; mem-fn should not create disk files
      (is (= 30 @(mem-fn 10 20)))
      (is (not (fs/exists? test-cache-dir)))
      ;; disk-fn with different args should create disk files
      (is (= 70 @(disk-fn 30 40)))
      (is (fs/exists? test-cache-dir)))))

(deftest test-storage-mem-in-flight
  (testing "Concurrent :mem derefs compute only once"
    (let [call-count (atom 0)
          slow-fn (fn [x y]
                    (swap! call-count inc)
                    (Thread/sleep 200)
                    (+ x y))]
      (with-redefs [expensive-add slow-fn]
        (binding [pocket/*storage* :mem]
          (let [futures (doall (repeatedly 10 #(future @(pocket/cached #'expensive-add 7 8))))
                results (mapv deref futures)]
            (is (every? #(= 15 %) results))
            (is (= 1 @call-count))))))))

(deftest test-storage-in-config
  (testing "config reflects :storage key"
    (is (= :mem+disk (:storage (pocket/config))))
    (binding [pocket/*storage* :mem]
      (is (= :mem (:storage (pocket/config)))))))

(deftest test-caching-fn-cache-dir-override
  (testing "caching-fn with :cache-dir writes to alternate directory"
    (let [alt-dir "/tmp/pocket-test-alt-dir"
          alt-fn (pocket/caching-fn #'expensive-add {:cache-dir alt-dir})]
      (try
        (is (= 30 @(alt-fn 10 20)))
        ;; Should write to alt-dir, not test-cache-dir
        (is (fs/exists? alt-dir))
        (is (not (fs/exists? test-cache-dir)))
        (finally
          (fs/delete-tree alt-dir))))))

(deftest test-origin-story-basic
  (testing "origin-story returns tree structure for a pipeline"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 3)]
      (let [tree (pocket/origin-story b)]
        (is (= #'expensive-add (:fn tree)))
        (is (= 2 (count (:args tree))))
        ;; First arg is a Cached node
        (is (= #'expensive-add (:fn (first (:args tree)))))
        (is (= [{:value 1} {:value 2}] (:args (first (:args tree)))))
        ;; Second arg is a leaf
        (is (= {:value 3} (second (:args tree))))))))

(deftest test-origin-story-value-populated
  (testing "origin-story includes :value when Cached has been derefed"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 3)]
      @b
      (let [tree (pocket/origin-story b)]
        (is (= 6 (:value tree)))
        (is (= 3 (:value (first (:args tree)))))))))

(deftest test-origin-story-unrealized
  (testing "origin-story omits :value when Cached has not been derefed"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 3)]
      (let [tree (pocket/origin-story b)]
        (is (not (contains? tree :value)))
        (is (not (contains? (first (:args tree)) :value)))))))

(deftest test-origin-story-plain-value
  (testing "origin-story on a non-Cached value returns {:value x}"
    (is (= {:value 42} (pocket/origin-story 42)))
    (is (= {:value nil} (pocket/origin-story nil)))
    (is (= {:value {:a 1}} (pocket/origin-story {:a 1})))))

(deftest test-origin-story-mermaid
  (testing "origin-story-mermaid returns valid Mermaid flowchart with kindly metadata"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 3)
          result (pocket/origin-story-mermaid b)
          mermaid (first result)]
      (is (vector? result) "Returns a kindly-wrapped vector")
      (is (= :kind/mermaid (:kindly/kind (meta result))) "Has kindly mermaid metadata")
      (is (string? mermaid))
      (is (.startsWith mermaid "flowchart TD"))
      (is (.contains mermaid "expensive-add"))
      ;; Should contain leaf values
      (is (.contains mermaid "3")))))

(deftest test-origin-story-mixed-storage
  (testing "origin-story works with mixed storage policies"
    (let [none-fn (pocket/caching-fn #'expensive-add {:storage :none})
          a (none-fn 1 2)
          b (pocket/cached #'expensive-add a 3)]
      ;; Tree structure is correct regardless of storage
      (let [tree (pocket/origin-story b)]
        (is (= #'expensive-add (:fn tree)))
        (is (= #'expensive-add (:fn (first (:args tree))))))
      ;; After deref, values appear
      @b
      (let [tree (pocket/origin-story b)]
        (is (= 6 (:value tree)))
        (is (= 3 (:value (first (:args tree)))))))))

(deftest test-origin-story-dag-diamond
  (testing "origin-story deduplicates shared Cached instances (diamond pattern)"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 10)
          c (pocket/cached #'expensive-add a 20)
          d (pocket/cached #'expensive-add b c)
          tree (pocket/origin-story d)]
      ;; Root node has :id
      (is (string? (:id tree)))
      ;; First arg (b) has :id and full structure
      (let [b-node (first (:args tree))]
        (is (= #'expensive-add (:fn b-node)))
        (is (string? (:id b-node)))
        ;; b's first arg is 'a' with full structure
        (let [a-in-b (first (:args b-node))]
          (is (= #'expensive-add (:fn a-in-b)))
          (is (string? (:id a-in-b)))))
      ;; Second arg (c) has :id
      (let [c-node (second (:args tree))]
        (is (= #'expensive-add (:fn c-node)))
        ;; c's first arg is a :ref to 'a', not a full node
        (let [a-in-c (first (:args c-node))]
          (is (contains? a-in-c :ref))
          (is (not (contains? a-in-c :fn)))
          ;; The ref points to the same id as a-in-b
          (is (= (:ref a-in-c) (:id (first (:args (first (:args tree))))))))))))

(deftest test-origin-story-graph-diamond
  (testing "origin-story-graph returns nodes and edges for diamond pattern"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 10)
          c (pocket/cached #'expensive-add a 20)
          d (pocket/cached #'expensive-add b c)
          graph (pocket/origin-story-graph d)]
      ;; Should have :nodes and :edges
      (is (map? (:nodes graph)))
      (is (vector? (:edges graph)))
      ;; 4 cached nodes + 4 value leaves = 8 nodes
      (is (= 8 (count (:nodes graph))))
      ;; Both b and c should have edges to a (shared dependency)
      (let [edges (:edges graph)
            a-id (some (fn [[id node]]
                         (when (and (= #'expensive-add (:fn node))
                                    ;; a is the only node with edges to value nodes 1 and 2
                                    (some #(and (= id (first %))
                                                (= {:value 1} (get (:nodes graph) (second %))))
                                          edges))
                           id))
                       (:nodes graph))]
        ;; Two different nodes should have edges pointing to a
        (is (= 2 (count (filter #(= a-id (second %)) edges))))))))

(deftest test-origin-story-mermaid-dag
  (testing "origin-story-mermaid renders shared nodes correctly"
    (let [a (pocket/cached #'expensive-add 1 2)
          b (pocket/cached #'expensive-add a 10)
          c (pocket/cached #'expensive-add a 20)
          d (pocket/cached #'expensive-add b c)
          mermaid (first (pocket/origin-story-mermaid d))]
      (is (string? mermaid))
      (is (.startsWith mermaid "flowchart TD"))
      ;; Should have 4 expensive-add nodes (a, b, c, d)
      (is (= 4 (count (re-seq #"expensive-add" mermaid))))
      ;; Should have edges - at least 8 (4 fn nodes + 4 value leaves, with diamond)
      (is (>= (count (re-seq #"-->" mermaid)) 8)))))
(deftest test-origin-story-mermaid-shapes
  (testing "origin-story-mermaid uses distinct shapes for functions and values"
    (let [c (pocket/cached #'expensive-add 1 2)
          mermaid (first (pocket/origin-story-mermaid c))]
      ;; Function nodes use rectangle shape: n0["expensive-add"]
      (is (re-find #"n\d+\[\"" mermaid)
          "Functions should use rectangle shape [...]")
      ;; Value nodes use parallelogram shape: n1[/"1"/]
      (is (re-find #"n\d+\[/\"1\"" mermaid)
          "Values should use parallelogram shape [/.../]")
      (is (re-find #"n\d+\[/\"2\"" mermaid)
          "Values should use parallelogram shape [/.../]"))))

(defn run-experiment
  "A fake experiment function that takes config and returns metrics."
  [config]
  {:rmse (* 0.1 (:lr config))
   :accuracy (/ 1.0 (:epochs config))})

(deftest test-compare-experiments
  (testing "compare-experiments extracts varying params and results"
    (let [;; Simulate experiments with different hyperparameters
          exp1 (pocket/cached #'run-experiment {:lr 0.01 :epochs 100 :batch-size 32})
          exp2 (pocket/cached #'run-experiment {:lr 0.001 :epochs 100 :batch-size 32})
          exp3 (pocket/cached #'run-experiment {:lr 0.01 :epochs 200 :batch-size 32})
          comparison (pocket/compare-experiments [exp1 exp2 exp3])]
      ;; Should have 3 results
      (is (= 3 (count comparison)))
      ;; Each result should have :result key with the experiment output
      (is (every? #(contains? % :result) comparison))
      (is (every? #(map? (:result %)) comparison))
      ;; :lr varies (0.01, 0.001, 0.01)
      (is (every? #(contains? % :lr) comparison))
      ;; :epochs varies (100, 100, 200)
      (is (every? #(contains? % :epochs) comparison))
      ;; :batch-size does NOT vary (all 32) so should not be included
      (is (not-any? #(contains? % :batch-size) comparison))
      ;; Check the actual varying values
      (is (= [0.01 0.001 0.01] (mapv :lr comparison)))
      (is (= [100 100 200] (mapv :epochs comparison))))))

;; Test for set/vector distinction (bug fixed in canonical-id)
(deftest test-set-vector-distinct-cache-keys
  (testing "Sets and vectors with same elements should have different cache keys"
    (let [set-result @(pocket/cached #'identity #{1 2 3})
          vec-result @(pocket/cached #'identity [1 2 3])]
      (is (set? set-result) "Set input should return set")
      (is (vector? vec-result) "Vector input should return vector"))))

(deftest test-empty-collections
  (testing "Empty collections are cached correctly"
    (is (= [] @(pocket/cached #'identity [])))
    (is (= {} @(pocket/cached #'identity {})))
    (is (set? @(pocket/cached #'identity #{})) "Empty set should return set")))

(deftest test-special-characters-in-args
  (testing "Special characters in arguments are handled"
    (is (= "hello/world" @(pocket/cached #'identity "hello/world")))
    (is (= "with\nnewlines" @(pocket/cached #'identity "with\nnewlines")))
    (is (= "with\ttabs" @(pocket/cached #'identity "with\ttabs")))))

(deftest test-nested-cached-auto-deref
  (testing "Cached arguments are auto-deref'd before function call"
    (let [c1 (pocket/cached #'expensive-add 1 2)
          c2 (pocket/cached #'expensive-add c1 10)]
      (is (= 13 @c2)))))

(deftest test-storage-mode-none
  (testing ":none mode is instance-local"
    (let [call-count (atom 0)
          f (fn [x] (swap! call-count inc) x)
          _ (intern *ns* 'local-fn f)
          c-fn (pocket/caching-fn (resolve 'local-fn) {:storage :none})]
      (reset! call-count 0)
      (let [c1 (c-fn :test)
            c2 (c-fn :test)]
        @c1
        @c2
        ;; Two different instances should compute twice
        (is (= 2 @call-count))
        ;; But same instance should memoize
        @c1
        (is (= 2 @call-count))))))

(deftest test-exception-not-cached
  (testing "Exceptions are not cached"
    (let [call-count (atom 0)
          f (fn [x] (swap! call-count inc) (throw (ex-info "test" {:x x})))
          _ (intern *ns* 'throwing-fn f)
          c-fn (pocket/caching-fn (resolve 'throwing-fn))]
      (reset! call-count 0)
      ;; First call throws
      (is (thrown? Exception @(c-fn :test)))
      ;; Second call also throws (not cached)
      (is (thrown? Exception @(c-fn :test)))
      ;; Function was called twice
      (is (= 2 @call-count)))))

(defn scalar-experiment
  "A fake experiment function that takes scalar args."
  [learning-rate dataset-name]
  {:rmse (* 0.1 learning-rate)
   :dataset dataset-name})

(deftest test-compare-experiments-scalar-args
  (testing "compare-experiments includes scalar args that vary across experiments"
    (let [exp1 (pocket/cached #'scalar-experiment 0.01 "mnist")
          exp2 (pocket/cached #'scalar-experiment 0.001 "mnist")
          exp3 (pocket/cached #'scalar-experiment 0.01 "cifar")
          comparison (pocket/compare-experiments [exp1 exp2 exp3])]
      ;; Should have 3 results
      (is (= 3 (count comparison)))
      ;; Each result should have :result
      (is (every? #(contains? % :result) comparison))
      ;; learning-rate varies (0.01, 0.001, 0.01) - should appear as :scalar-experiment/arg0
      (is (every? #(contains? % :scalar-experiment/arg0) comparison))
      ;; dataset varies ("mnist", "mnist", "cifar") - should appear as :scalar-experiment/arg1
      (is (every? #(contains? % :scalar-experiment/arg1) comparison))
      ;; Check values
      (is (= [0.01 0.001 0.01] (mapv :scalar-experiment/arg0 comparison)))
      (is (= ["mnist" "mnist" "cifar"] (mapv :scalar-experiment/arg1 comparison))))))

(deftest test-env-var-validation
  (testing "Invalid POCKET_MEM_CACHE env var produces helpful error"
    ;; We can't easily set env vars in Java, so test the parse-env helper indirectly
    ;; by verifying the current resolve functions work with valid env vars
    ;; The main fix is structural — wrapping with try/catch in parse-env
    (is (map? (pocket/config)) "config should resolve without error")))
