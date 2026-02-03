(ns
 pocket-book.getting-started-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v3_l18 (def cache-dir "/tmp/pocket-demo"))


(def v4_l20 (pocket/set-base-cache-dir! cache-dir))


(def v6_l23 (pocket/cleanup!))


(def
 v7_l25
 (defn
  expensive-calculation
  "Simulates an expensive computation"
  [x y]
  (println (str "Computing " x " + " y " (this is expensive!)"))
  (Thread/sleep 400)
  (+ x y)))


(def
 v9_l46
 (def cached-result (pocket/cached #'expensive-calculation 10 20)))


(def v10_l49 (type cached-result))


(deftest t11_l51 (is (= v10_l49 scicloj.pocket.impl.cache.Cached)))


(def v13_l54 (time @cached-result))


(deftest t14_l56 (is (= v13_l54 30)))


(def v16_l59 (time @cached-result))


(def
 v18_l66
 (def cached-expensive (pocket/caching-fn #'expensive-calculation)))


(def v20_l70 (time @(cached-expensive 5 15)))


(def v22_l73 (time @(cached-expensive 5 15)))


(def v24_l76 (time @(cached-expensive 7 8)))


(def v26_l84 (defn returns-nil [] nil))


(def v27_l86 (def nil-result (pocket/cached #'returns-nil)))


(def v29_l89 (deref nil-result))


(deftest t30_l91 (is (nil? v29_l89)))


(def v32_l94 (deref nil-result))


(def v34_l131 (deref (pocket/cached #'expensive-calculation 10 20)))


(def v35_l133 (pocket/invalidate! #'expensive-calculation 10 20))


(def v37_l137 (deref (pocket/cached #'expensive-calculation 1 2)))


(def v38_l138 (deref (pocket/cached #'expensive-calculation 3 4)))


(def v39_l140 (pocket/invalidate-fn! #'expensive-calculation))


(def v41_l151 (defn process-data [data opts] (:data data)))


(def
 v43_l156
 (deref
  (pocket/cached
   #'process-data
   {:data [1 2 3]}
   {:scale 2, :version 3})))


(def v45_l170 (kind/code (pocket/dir-tree)))


(def
 v47_l179
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v49_l189 (pocket/cache-entries))


(def v51_l193 (pocket/cache-stats))


(def
 v53_l202
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v54_l205 (def long-text (apply str (repeat 300 "x"))))


(def v55_l207 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t56_l209
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v55_l207)))


(def v58_l213 (kind/code (pocket/dir-tree)))


(def
 v60_l218
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t61_l222
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v60_l218)))


(def v63_l226 (pocket/cleanup!))
