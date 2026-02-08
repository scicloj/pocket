(ns
 pocket-book.pocket-model-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset.modelling :as ds-mod]
  [tech.v3.dataset.column-filters :as cf]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.metamorph.ml.regression]
  [scicloj.metamorph.core :as mm]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v2_l79 (def cache-dir "/tmp/pocket-model"))


(def v3_l81 (pocket/set-base-cache-dir! cache-dir))


(def v4_l83 (pocket/cleanup!))


(def
 v6_l97
 (defn
  pocket-model
  "Drop-in replacement for ml/model that caches training via Pocket.\n  Falls back to uncached training if serialization fails."
  [options]
  (fn
   [{:metamorph/keys [id data mode], :as ctx}]
   (case
    mode
    :fit
    (let
     [model
      (try
       (deref (pocket/cached #'ml/train data options))
       (catch Exception _e (ml/train data options)))]
     (assoc
      ctx
      id
      (assoc
       model
       :scicloj.metamorph.ml/unsupervised?
       (get (ml/options->model-def options) :unsupervised? false))))
    :transform
    (let
     [model (get ctx id)]
     (if
      (get model :scicloj.metamorph.ml/unsupervised?)
      ctx
      (->
       ctx
       (update
        id
        assoc
        :scicloj.metamorph.ml/feature-ds
        (cf/feature data)
        :scicloj.metamorph.ml/target-ds
        (cf/target data))
       (assoc :metamorph/data (ml/predict data model)))))))))


(def
 v8_l128
 (def
  ds
  (->
   (let
    [rng (java.util.Random. 42)]
    (tc/dataset
     {:x (vec (repeatedly 200 (fn* [] (* 10.0 (.nextDouble rng))))),
      :y
      (vec
       (repeatedly
        200
        (fn*
         []
         (+
          (* 3.0 (* 10.0 (.nextDouble rng)))
          (* 2.0 (.nextGaussian rng))))))}))
   (ds-mod/set-inference-target :y))))


(def v9_l135 (def splits (tc/split->seq ds :kfold {:k 3, :seed 42})))


(def v10_l137 (count splits))


(deftest t11_l139 (is ((fn [n] (= n 3)) v10_l137)))


(def
 v13_l150
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v14_l157
 (def
  pipe-cart
  (mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))))


(def
 v16_l164
 (def
  results-1
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v17_l173
 (mapv
  (fn* [p1__69836#] (-> p1__69836# :test-transform :metric))
  (flatten results-1)))


(deftest
 t18_l175
 (is
  ((fn [ms] (every? (fn* [p1__69837#] (< p1__69837# 15)) ms))
   v17_l173)))


(def v20_l180 (pocket/cache-stats))


(deftest
 t21_l182
 (is ((fn [stats] (= 3 (:total-entries stats))) v20_l180)))


(def
 v23_l187
 (def
  results-2
  (ml/evaluate-pipelines
   [pipe-cart]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v24_l196
 (=
  (mapv
   (fn* [p1__69838#] (-> p1__69838# :test-transform :metric))
   (flatten results-1))
  (mapv
   (fn* [p1__69839#] (-> p1__69839# :test-transform :metric))
   (flatten results-2))))


(deftest t25_l199 (is ((fn [eq] (true? eq)) v24_l196)))


(def v27_l209 (pocket/cleanup!))


(def
 v28_l211
 (defn
  cart-pipe
  [max-depth]
  (mm/pipeline
   #:metamorph{:id :model}
   (pocket-model
    {:model-type :scicloj.ml.tribuo/regression,
     :tribuo-components
     [{:name "cart",
       :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
       :properties {:maxDepth (str max-depth)}}],
     :tribuo-trainer-name "cart"}))))


(def
 v30_l223
 (def
  batch-1
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 8 12])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v32_l234 (pocket/cache-stats))


(deftest
 t33_l236
 (is ((fn [stats] (= 9 (:total-entries stats))) v32_l234)))


(def
 v35_l242
 (def
  batch-2
  (ml/evaluate-pipelines
   (mapv cart-pipe [4 6 8 10 12 16])
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v37_l253 (pocket/cache-stats))


(deftest
 t38_l255
 (is ((fn [stats] (= 18 (:total-entries stats))) v37_l253)))


(def
 v40_l260
 (let
  [depths
   [4 6 8 10 12 16]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__69840#] (-> p1__69840# :test-transform :metric))
       pipeline-results)))
    batch-2)]
  (tc/dataset {:depth depths, :mean-rmse means})))


(deftest t41_l266 (is ((fn [ds] (= 6 (tc/row-count ds))) v40_l260)))


(def v43_l276 (pocket/cleanup!))


(def
 v44_l278
 (def
  sgd-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "squared",
     :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
    {:name "trainer",
     :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer",
     :properties
     {:objective "squared", :epochs "50", :loggingInterval "10000"}}],
   :tribuo-trainer-name "trainer"}))


(def
 v45_l289
 (def
  multi-results
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))
    (mm/pipeline #:metamorph{:id :model} (pocket-model sgd-spec))
    (mm/pipeline
     #:metamorph{:id :model}
     (pocket-model {:model-type :fastmath/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v47_l302 (pocket/cache-stats))


(deftest
 t48_l304
 (is ((fn [stats] (= 9 (:total-entries stats))) v47_l302)))


(def
 v50_l309
 (let
  [model-names
   ["CART" "SGD" "fastmath-OLS"]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__69841#] (-> p1__69841# :test-transform :metric))
       pipeline-results)))
    multi-results)]
  (tc/dataset {:model model-names, :mean-rmse means})))


(deftest t51_l315 (is ((fn [ds] (= 3 (tc/row-count ds))) v50_l309)))


(def v53_l327 (pocket/cleanup!))


(def
 v54_l329
 (def
  fallback-results
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))
    (mm/pipeline
     #:metamorph{:id :model}
     (pocket-model {:model-type :metamorph.ml/ols}))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v56_l344 (pocket/cache-stats))


(deftest
 t57_l346
 (is
  ((fn
    [stats]
    (=
     3
     (get-in stats [:entries-per-fn "scicloj.metamorph.ml/train"])))
   v56_l344)))


(def
 v59_l351
 (let
  [model-names
   ["CART" "OLS-fallback"]
   means
   (mapv
    (fn
     [pipeline-results]
     (tcc/mean
      (map
       (fn* [p1__69842#] (-> p1__69842# :test-transform :metric))
       pipeline-results)))
    fallback-results)]
  (tc/dataset {:model model-names, :mean-rmse means})))


(deftest t60_l357 (is ((fn [ds] (= 2 (tc/row-count ds))) v59_l351)))


(def v62_l367 (pocket/cleanup!))


(def
 v64_l370
 (def
  persist-results-1
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def v66_l380 (pocket/clear-mem-cache!))


(def
 v68_l383
 (def
  persist-results-2
  (ml/evaluate-pipelines
   [(mm/pipeline #:metamorph{:id :model} (pocket-model cart-spec))]
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v70_l393
 (=
  (mapv
   (fn* [p1__69843#] (-> p1__69843# :test-transform :metric))
   (flatten persist-results-1))
  (mapv
   (fn* [p1__69844#] (-> p1__69844# :test-transform :metric))
   (flatten persist-results-2))))


(deftest t71_l396 (is ((fn [eq] (true? eq)) v70_l393)))


(def v73_l426 (pocket/cleanup!))
