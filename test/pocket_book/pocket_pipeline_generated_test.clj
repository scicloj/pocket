(ns
 pocket-book.pocket-pipeline-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [scicloj.metamorph.core :as mm]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset.modelling :as ds-mod]
  [tech.v3.dataset.column-filters :as cf]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v3_l99 (pocket-book.logging/set-slf4j-level! :info))


(def v4_l101 (def cache-dir "/tmp/pocket-metamorph"))


(def v5_l103 (pocket/set-base-cache-dir! cache-dir))


(def v6_l105 (pocket/cleanup!))


(def
 v8_l117
 (defn
  make-regression-data
  "Generate synthetic regression data: y = f(x) + noise.\n  Optional outlier injection on x values."
  [{:keys [f n noise-sd seed outlier-fraction outlier-scale],
    :or {outlier-fraction 0, outlier-scale 10}}]
  (let
   [rng
    (java.util.Random. (long seed))
    xs
    (repeatedly n (fn* [] (* 10.0 (.nextDouble rng))))
    xs-final
    (if
     (pos? outlier-fraction)
     (let
      [out-rng (java.util.Random. (+ (long seed) 7919))]
      (map
       (fn
        [x]
        (if
         (< (.nextDouble out-rng) outlier-fraction)
         (+ x (* (double outlier-scale) (.nextGaussian out-rng)))
         x))
       xs))
     xs)
    ys
    (map
     (fn
      [x]
      (+ (double (f x)) (* (double noise-sd) (.nextGaussian rng))))
     xs)]
   (->
    (tc/dataset {:x xs-final, :y ys})
    (ds-mod/set-inference-target :y)))))


(def v9_l138 (defn nonlinear-fn [x] (* (Math/sin x) x)))


(def
 v10_l140
 (defn
  split-dataset
  "Split into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v11_l145
 (defn
  prepare-features
  "Add derived columns: :raw (none), :poly+trig (x², sin, cos)."
  [ds feature-set]
  (let
   [x (:x ds)]
   (->
    (case
     feature-set
     :raw
     ds
     :poly+trig
     (tc/add-columns
      ds
      {:x2 (tcc/sq x), :sin-x (tcc/sin x), :cos-x (tcc/cos x)}))
    (ds-mod/set-inference-target :y)))))


(def
 v12_l156
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data."
  [train-ds]
  (let
   [xs
    (sort (:x train-ds))
    n
    (count xs)
    q1
    (nth xs (int (* 0.25 n)))
    q3
    (nth xs (int (* 0.75 n)))
    iqr
    (- q3 q1)]
   {:lower (- q1 (* 1.5 iqr)), :upper (+ q3 (* 1.5 iqr))})))


(def
 v13_l167
 (defn
  clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (let
   [{:keys [lower upper]} threshold]
   (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper))))))


(def
 v14_l173
 (defn
  predict-model
  "Predict on test data using a trained model."
  [test-ds model]
  (ml/predict test-ds model)))


(def
 v16_l180
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v17_l187
 (def
  linear-sgd-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "squared",
     :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
    {:name "linear-sgd",
     :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer",
     :properties
     {:objective "squared", :epochs "50", :loggingInterval "10000"}}],
   :tribuo-trainer-name "linear-sgd"}))


(def
 v19_l210
 (def
  c-fit-outlier-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem})))


(def
 v20_l213
 (def
  c-clip-outliers
  (pocket/caching-fn #'clip-outliers {:storage :mem})))


(def
 v21_l216
 (def
  c-prepare-features
  (pocket/caching-fn #'prepare-features {:storage :mem})))


(def
 v22_l219
 (def
  c-predict-model
  (pocket/caching-fn #'predict-model {:storage :mem})))


(def
 v24_l249
 (defn
  pocket-fitted
  "Create a stateful pipeline step from fit and apply caching-fns.\n  In :fit mode, fits parameters from data and applies them.\n  In :transform mode, applies previously fitted parameters.\n  Results are derefed so real datasets flow through the pipeline."
  [fit-caching-fn apply-caching-fn]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (let
     [fitted (deref (fit-caching-fn data))]
     (->
      ctx
      (assoc id fitted)
      (assoc :metamorph/data (deref (apply-caching-fn data fitted)))))
    :transform
    (assoc
     ctx
     :metamorph/data
     (deref (apply-caching-fn data (get ctx id))))))))


(def
 v26_l276
 (defn
  pocket-model
  "Cached model step compatible with ml/evaluate-pipelines.\n  Caches training and prediction via pocket/cached. Stores the\n  training Cached reference at :pocket/model-cached for provenance."
  [model-spec]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (let
     [model-c
      (pocket/cached #'ml/train data model-spec)
      model
      (deref model-c)]
     (assoc ctx id model :pocket/model-cached model-c))
    :transform
    (let
     [model (get ctx id)]
     (->
      ctx
      (update
       id
       assoc
       :scicloj.metamorph.ml/feature-ds
       (cf/feature data)
       :scicloj.metamorph.ml/target-ds
       (cf/target data))
      (assoc
       :metamorph/data
       (deref (c-predict-model data model))
       :target
       data)))))))


(def
 v28_l306
 (def
  data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn,
    :n 500,
    :noise-sd 0.5,
    :seed 42,
    :outlier-fraction 0.1,
    :outlier-scale 15})))


(def
 v29_l311
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v30_l312 (def train-c (pocket/cached :train split-c)))


(def v31_l313 (def test-c (pocket/cached :test split-c)))


(def
 v32_l315
 (def
  pipe-cart
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift (comp deref c-prepare-features) :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def v34_l325 (def fit-ctx (mm/fit-pipe (deref train-c) pipe-cart)))


(def
 v36_l329
 (def
  transform-ctx
  (mm/transform-pipe (deref test-c) pipe-cart fit-ctx)))


(def
 v38_l333
 (->
  fit-ctx
  (get :model)
  (update :model-data dissoc :model-as-bytes)
  kind/pprint))


(deftest t39_l338 (is ((fn [model] (map? model)) v38_l333)))


(def v41_l343 (tc/head (:metamorph/data transform-ctx)))


(def
 v43_l347
 (loss/rmse
  (:y (get-in transform-ctx [:model :scicloj.metamorph.ml/target-ds]))
  (:y (:metamorph/data transform-ctx))))


(deftest t44_l350 (is ((fn [rmse] (< rmse 5.0)) v43_l347)))


(def
 v46_l360
 (defn
  compute-loss
  "Compute RMSE between actual and predicted :y columns."
  [actual-ds predicted-ds]
  (loss/rmse (:y actual-ds) (:y predicted-ds))))


(def
 v47_l365
 (def
  c-compute-loss
  (pocket/caching-fn #'compute-loss {:storage :mem})))


(def
 v49_l371
 (def
  train-transform-ctx
  (mm/transform-pipe (deref train-c) pipe-cart fit-ctx)))


(def
 v51_l375
 (def
  train-loss-c
  (c-compute-loss
   (:target train-transform-ctx)
   (:metamorph/data train-transform-ctx))))


(def
 v52_l379
 (def
  test-loss-c
  (c-compute-loss
   (:target transform-ctx)
   (:metamorph/data transform-ctx))))


(def
 v54_l385
 (defn
  report
  "Gather train and test loss into a summary map."
  [train-loss test-loss]
  {:train-rmse train-loss, :test-rmse test-loss}))


(def
 v55_l391
 (def c-report (pocket/caching-fn #'report {:storage :mem})))


(def v56_l393 (def summary-c (c-report train-loss-c test-loss-c)))


(def v57_l395 (deref summary-c))


(deftest
 t58_l397
 (is
  ((fn
    [{:keys [train-rmse test-rmse]}]
    (and (< train-rmse test-rmse) (< test-rmse 5.0)))
   v57_l395)))


(def v60_l409 (pocket/origin-story-mermaid summary-c))


(def v61_l411 (pocket/cleanup!))


(def
 v63_l423
 (defn
  nth-split-train
  "Extract the train set of the nth split."
  [ds split-method split-params idx]
  (:train (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v64_l428
 (defn
  nth-split-test
  "Extract the test set of the nth split."
  [ds split-method split-params idx]
  (:test (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v65_l433
 (defn-
  n-splits
  "Derive the number of splits from the method and params.\n  For :loo, derefs the dataset to get its row count."
  [data-c split-method split-params]
  (case
   split-method
   :kfold
   (:k split-params 5)
   :holdout
   1
   :bootstrap
   (:repeats split-params 1)
   :loo
   (tc/row-count (deref data-c)))))


(def
 v66_l443
 (defn
  pocket-splits
  "Create k-fold splits as Cached references.\n  Returns [{:train Cached, :test Cached, :idx int} ...]."
  [data-c split-method split-params]
  (for
   [idx (range (n-splits data-c split-method split-params))]
   {:train
    (pocket/cached
     #'nth-split-train
     data-c
     split-method
     split-params
     idx),
    :test
    (pocket/cached
     #'nth-split-test
     data-c
     split-method
     split-params
     idx),
    :idx idx})))


(def
 v68_l458
 (def cached-splits (pocket-splits data-c :kfold {:k 3, :seed 42})))


(def
 v69_l460
 (def
  splits
  (map
   (fn
    [{:keys [train test]}]
    {:train (deref train), :test (deref test)})
   cached-splits)))


(def
 v71_l475
 (defn
  make-pipe
  [{:keys [feature-set model-spec]}]
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift (comp deref c-prepare-features) feature-set)
   #:metamorph{:id :model}
   (pocket-model model-spec))))


(def
 v72_l481
 (def
  configs
  [{:feature-set :poly+trig, :model-spec cart-spec}
   {:feature-set :raw, :model-spec cart-spec}
   {:feature-set :poly+trig, :model-spec linear-sgd-spec}]))


(def
 v73_l486
 (def
  results
  (ml/evaluate-pipelines
   (map make-pipe configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v75_l497
 (def
  summary
  (map
   (fn
    [config pipeline-results]
    {:feature-set (:feature-set config),
     :model-type (-> config :model-spec :tribuo-trainer-name),
     :mean-rmse
     (tcc/mean
      (map
       (fn* [p1__71715#] (-> p1__71715# :test-transform :metric))
       pipeline-results))})
   configs
   results)))


(def v76_l505 (tc/dataset summary))


(deftest t77_l507 (is ((fn [ds] (= 3 (tc/row-count ds))) v76_l505)))


(def
 v79_l512
 (def
  results-2
  (ml/evaluate-pipelines
   (map make-pipe configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v80_l521
 (=
  (map
   (fn* [p1__71716#] (-> p1__71716# first :test-transform :metric))
   results)
  (map
   (fn* [p1__71717#] (-> p1__71717# first :test-transform :metric))
   results-2)))


(deftest t81_l524 (is ((fn [eq] (true? eq)) v80_l521)))


(def
 v83_l534
 (def
  sweep-configs
  (for
   [depth [4 6 8 12] fs [:raw :poly+trig]]
   {:feature-set fs,
    :model-spec
    {:model-type :scicloj.ml.tribuo/regression,
     :tribuo-components
     [{:name "cart",
       :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
       :properties {:maxDepth (str depth)}}],
     :tribuo-trainer-name "cart"}})))


(def
 v84_l544
 (def
  sweep-results
  (ml/evaluate-pipelines
   (map make-pipe sweep-configs)
   splits
   loss/rmse
   :loss
   {:return-best-crossvalidation-only false,
    :return-best-pipeline-only false})))


(def
 v86_l555
 (def
  sweep-summary
  (->>
   (map
    (fn
     [config pipeline-results]
     {:depth
      (->
       config
       :model-spec
       :tribuo-components
       first
       :properties
       :maxDepth),
      :feature-set (:feature-set config),
      :mean-rmse
      (tcc/mean
       (map
        (fn* [p1__71718#] (-> p1__71718# :test-transform :metric))
        pipeline-results))})
    sweep-configs
    sweep-results)
   (sort-by :mean-rmse))))


(def v87_l565 (tc/dataset sweep-summary))


(deftest t88_l567 (is ((fn [ds] (= 8 (tc/row-count ds))) v87_l565)))


(def
 v90_l580
 (pocket/origin-story-mermaid
  (:pocket/model-cached (-> sweep-results first first :fit-ctx))))


(def v92_l642 (pocket/cleanup!))
