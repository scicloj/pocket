(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l16 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l17 (pocket/set-base-cache-dir! test-dir))


(def v5_l63 (defn example-fn [x] (* x x)))


(def
 v6_l65
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l70
 (is
  ((fn* [p1__28905#] (re-find #"requires a var" p1__28905#)) v6_l65)))


(def v9_l82 (pocket/cleanup!))


(def v10_l84 (defn transform [x] (* x 2)))


(def v12_l87 (deref (pocket/cached #'transform 10)))


(deftest t13_l89 (is (= v12_l87 20)))


(def v15_l92 (pocket/invalidate! #'transform 10))


(def v17_l96 (deref (pocket/cached #'transform 1)))


(def v18_l97 (deref (pocket/cached #'transform 2)))


(def v19_l99 (pocket/invalidate-fn! #'transform))


(def v21_l106 (pocket/cleanup!))


(def
 v22_l108
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l113
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l115 (is (= v24_l113 {:result 6, :version 1})))


(def
 v27_l118
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l120 (is (= v27_l118 {:result 6, :version 2})))


(def v30_l128 (pocket/cleanup!))


(def v32_l160 (pocket/cleanup!))


(def v33_l162 (def call-count (atom 0)))


(def v34_l164 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l169 (reset! call-count 0))


(def
 v37_l171
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l175 (is (= v37_l171 {:result 25, :calls 1})))


(def
 v40_l178
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l182 (is (= v40_l178 {:result 25, :calls 1})))


(def v43_l190 (pocket/cleanup!))


(def v44_l191 (deref (pocket/cached #'transform 1)))


(def v45_l192 (deref (pocket/cached #'transform 2)))


(def v46_l193 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l196 (count (pocket/cache-entries)))


(deftest t49_l198 (is (= v48_l196 3)))


(def v51_l201 (:total-entries (pocket/cache-stats)))


(deftest t52_l203 (is (= v51_l201 3)))


(def v54_l206 (kind/code (pocket/dir-tree)))


(def
 v56_l211
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l220 (pocket/cache-entries))


(def v60_l224 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l230 (pocket/cleanup!))


(def v63_l232 (def pending-value (pocket/cached #'transform 99)))


(def v65_l235 (pr-str pending-value))


(deftest
 t66_l237
 (is ((fn* [p1__28906#] (re-find #":pending" p1__28906#)) v65_l235)))


(def v67_l239 (deref pending-value))


(def v69_l242 (pr-str pending-value))


(deftest
 t70_l244
 (is ((fn* [p1__28907#] (re-find #":cached" p1__28907#)) v69_l242)))


(def v72_l264 (pocket/cleanup!))


(def
 v73_l266
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l269 (def long-text (apply str (repeat 300 "x"))))


(def v75_l271 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l273
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l271)))


(def v78_l277 (kind/code (pocket/dir-tree)))


(def
 v80_l282
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l286
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l282)))


(def v83_l316 (pocket/cleanup!))


(def v84_l318 (defn generate-data [n] (doall (range n))))


(def v85_l322 (deref (pocket/cached #'generate-data 5)))


(deftest t86_l324 (is (= v85_l322 [0 1 2 3 4])))


(def v88_l357 (pocket/cleanup!))
