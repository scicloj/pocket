(ns
 pocket-book.usage-practices-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [clojure.test :refer [deftest is]]))


(def v2_l20 (def test-dir "/tmp/pocket-dev-practices"))


(def v3_l21 (pocket/set-base-cache-dir! test-dir))


(def v5_l67 (defn example-fn [x] (* x x)))


(def
 v6_l69
 (try (pocket/cached example-fn 5) (catch Exception e (ex-message e))))


(deftest
 t7_l74
 (is
  ((fn* [p1__97020#] (re-find #"requires a var" p1__97020#)) v6_l69)))


(def v9_l86 (pocket/cleanup!))


(def v10_l88 (defn transform [x] (* x 2)))


(def v12_l91 (deref (pocket/cached #'transform 10)))


(deftest t13_l93 (is (= v12_l91 20)))


(def v15_l96 (pocket/invalidate! #'transform 10))


(def v17_l100 (deref (pocket/cached #'transform 1)))


(def v18_l101 (deref (pocket/cached #'transform 2)))


(def v19_l103 (pocket/invalidate-fn! #'transform))


(def v21_l110 (pocket/cleanup!))


(def
 v22_l112
 (defn
  process-data
  [{:keys [data version]}]
  {:result (reduce + data), :version version}))


(def
 v24_l117
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 1})))


(deftest t25_l119 (is (= v24_l117 {:result 6, :version 1})))


(def
 v27_l122
 (deref (pocket/cached #'process-data {:data [1 2 3], :version 2})))


(deftest t28_l124 (is (= v27_l122 {:result 6, :version 2})))


(def v30_l132 (pocket/cleanup!))


(def v32_l164 (pocket/cleanup!))


(def v33_l166 (def call-count (atom 0)))


(def v34_l168 (defn tracked-fn [x] (swap! call-count inc) (* x x)))


(def v36_l173 (reset! call-count 0))


(def
 v37_l175
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t38_l179 (is (= v37_l175 {:result 25, :calls 1})))


(def
 v40_l182
 (let
  [result (deref (pocket/cached #'tracked-fn 5)) calls @call-count]
  {:result result, :calls calls}))


(deftest t41_l186 (is (= v40_l182 {:result 25, :calls 1})))


(def v43_l194 (pocket/cleanup!))


(def v44_l195 (deref (pocket/cached #'transform 1)))


(def v45_l196 (deref (pocket/cached #'transform 2)))


(def v46_l197 (deref (pocket/cached #'tracked-fn 3)))


(def v48_l200 (count (pocket/cache-entries)))


(deftest t49_l202 (is (= v48_l200 3)))


(def v51_l205 (:total-entries (pocket/cache-stats)))


(deftest t52_l207 (is (= v51_l205 3)))


(def v54_l210 (kind/code (pocket/dir-tree)))


(def
 v56_l215
 (->
  (pocket/cache-entries)
  first
  :path
  (str "/meta.edn")
  slurp
  clojure.edn/read-string))


(def v58_l224 (pocket/cache-entries))


(def v60_l228 (pocket/cache-entries (str (ns-name *ns*) "/transform")))


(def v62_l234 (pocket/cleanup!))


(def v63_l236 (def pending-value (pocket/cached #'transform 99)))


(def v65_l239 (pr-str pending-value))


(deftest
 t66_l241
 (is ((fn* [p1__97021#] (re-find #":pending" p1__97021#)) v65_l239)))


(def v67_l243 (deref pending-value))


(def v69_l246 (pr-str pending-value))


(deftest
 t70_l248
 (is ((fn* [p1__97022#] (re-find #":cached" p1__97022#)) v69_l246)))


(def v72_l268 (pocket/cleanup!))


(def
 v73_l270
 (defn
  process-long-text
  [text]
  (str "Processed: " (count text) " chars")))


(def v74_l273 (def long-text (apply str (repeat 300 "x"))))


(def v75_l275 (deref (pocket/cached #'process-long-text long-text)))


(deftest
 t76_l277
 (is
  ((fn [result] (clojure.string/starts-with? result "Processed:"))
   v75_l275)))


(def v78_l281 (kind/code (pocket/dir-tree)))


(def
 v80_l286
 (->
  (pocket/cache-entries (str (ns-name *ns*) "/process-long-text"))
  first
  :fn-name))


(deftest
 t81_l290
 (is
  ((fn
    [fn-name]
    (and
     fn-name
     (clojure.string/ends-with? fn-name "/process-long-text")))
   v80_l286)))


(def v83_l320 (pocket/cleanup!))


(def v84_l322 (defn generate-data [n] (doall (range n))))


(def v85_l326 (deref (pocket/cached #'generate-data 5)))


(deftest t86_l328 (is (= v85_l326 [0 1 2 3 4])))


(def v88_l361 (pocket/cleanup!))
