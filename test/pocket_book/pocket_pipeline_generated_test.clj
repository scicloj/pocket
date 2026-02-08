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
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def
 v3_l85
 (try
  (let
   [level-field
    (.getDeclaredField org.slf4j.simple.SimpleLogger "currentLogLevel")
    config-field
    (.getDeclaredField org.slf4j.simple.SimpleLogger "CONFIG_PARAMS")]
   (.setAccessible level-field true)
   (.setAccessible config-field true)
   (let
    [config
     (.get config-field nil)
     dl
     (.getDeclaredField (class config) "defaultLogLevel")]
    (.setAccessible dl true)
    (.setInt dl config 20))
   (doseq
    [name ["scicloj.pocket" "scicloj.pocket.impl.cache"]]
    (.setInt level-field (org.slf4j.LoggerFactory/getLogger name) 20)))
  (catch Exception _)))


(def v4_l100 (def cache-dir "/tmp/pocket-metamorph"))


(def v5_l102 (pocket/set-base-cache-dir! cache-dir))


(def v6_l104 (pocket/cleanup!))


(def
 v8_l116
 (defn
  make-regression-data
  "Generate synthetic regression data: y = f(x) + noise.\n  Optional outlier injection on x values."
  [{:keys [f n noise-sd seed outlier-fraction outlier-scale],
    :or {outlier-fraction 0, outlier-scale 10}}]
  (let
   [rng
    (java.util.Random. (long seed))
    xs
    (vec (repeatedly n (fn* [] (* 10.0 (.nextDouble rng)))))
    xs-final
    (if
     (pos? outlier-fraction)
     (let
      [out-rng (java.util.Random. (+ (long seed) 7919))]
      (mapv
       (fn
        [x]
        (if
         (< (.nextDouble out-rng) outlier-fraction)
         (+ x (* (double outlier-scale) (.nextGaussian out-rng)))
         x))
       xs))
     xs)
    ys
    (mapv
     (fn
      [x]
      (+ (double (f x)) (* (double noise-sd) (.nextGaussian rng))))
     xs)]
   (->
    (tc/dataset {:x xs-final, :y ys})
    (ds-mod/set-inference-target :y)))))


(def v9_l137 (defn nonlinear-fn [x] (* (Math/sin x) x)))


(def
 v10_l139
 (defn
  split-dataset
  "Split into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v11_l144
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
 v12_l155
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data."
  [train-ds]
  (let
   [xs
    (sort (vec (:x train-ds)))
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
 v13_l166
 (defn
  clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (let
   [{:keys [lower upper]} threshold]
   (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper))))))


(def
 v14_l172
 (defn
  train-model
  "Train a model on a prepared dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v15_l177
 (defn
  predict-model
  "Predict on test data using a trained model."
  [test-ds model]
  (ml/predict test-ds model)))


(def
 v17_l184
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v18_l191
 (def
  linear-sgd-spec
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
 v20_l217
 (def
  c-fit-outlier-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem})))


(def
 v21_l220
 (def
  c-clip-outliers
  (pocket/caching-fn #'clip-outliers {:storage :mem})))


(def
 v22_l223
 (def
  c-prepare-features
  (pocket/caching-fn #'prepare-features {:storage :mem})))


(def v23_l226 (def c-train-model (pocket/caching-fn #'train-model)))


(def
 v24_l229
 (def
  c-predict-model
  (pocket/caching-fn #'predict-model {:storage :mem})))


(def
 v26_l256
 (defn
  pocket-fitted
  "Create a stateful pipeline step from fit and apply functions.\n  In :fit mode, fits parameters from data and applies them.\n  In :transform mode, applies previously fitted parameters."
  [fit-caching-fn apply-caching-fn]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (let
     [fitted (fit-caching-fn data)]
     (->
      ctx
      (assoc id fitted)
      (assoc :metamorph/data (apply-caching-fn data fitted))))
    :transform
    (assoc ctx :metamorph/data (apply-caching-fn data (get ctx id)))))))


(def
 v28_l275
 (defn
  pocket-model
  "Create a model pipeline step. Trains in :fit mode,\n  predicts in :transform mode. Like ml/model."
  [model-spec]
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (assoc ctx id (c-train-model data model-spec))
    :transform
    (let
     [model (get ctx id)]
     (assoc
      ctx
      :target
      data
      :metamorph/data
      (c-predict-model data model)))))))


(def
 v30_l293
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
 v31_l298
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v32_l299 (def train-c (pocket/cached :train split-c)))


(def v33_l300 (def test-c (pocket/cached :test split-c)))


(def
 v34_l302
 (def
  pipe-cart
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift c-prepare-features :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def v36_l310 (def fit-ctx (mm/fit-pipe train-c pipe-cart)))


(def
 v38_l314
 (def transform-ctx (mm/transform-pipe test-c pipe-cart fit-ctx)))


(def v40_l318 (kind/pprint @(:model fit-ctx)))


(deftest t41_l320 (is ((fn [model] (map? model)) v40_l318)))


(def v43_l325 (tc/head (deref (:metamorph/data transform-ctx))))


(def
 v45_l329
 (loss/rmse
  (:y @(:target transform-ctx))
  (:y @(:metamorph/data transform-ctx))))


(deftest t46_l332 (is ((fn [rmse] (< rmse 5.0)) v45_l329)))


(def v48_l341 (pocket/origin-story-mermaid (:model fit-ctx)))


(def v49_l343 (pocket/cleanup!))


(def
 v51_l352
 (defn
  nth-split-train
  "Extract the train set of the nth split."
  [ds split-method split-params idx]
  (:train (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v52_l357
 (defn
  nth-split-test
  "Extract the test set of the nth split."
  [ds split-method split-params idx]
  (:test (nth (tc/split->seq ds split-method split-params) idx))))


(def
 v53_l362
 (defn-
  n-splits
  "Derive the number of splits from the method and params,\n  without materializing the dataset."
  [split-method split-params]
  (case
   split-method
   :kfold
   (:k split-params 5)
   :holdout
   1
   :bootstrap
   (:repeats split-params 1)
   :loo
   (throw
    (ex-info
     "pocket-splits does not support :loo (needs dataset size)"
     {})))))


(def
 v54_l372
 (defn
  pocket-splits
  "Create k-fold splits as Cached references.\n  Returns [{:train Cached, :test Cached, :idx int} ...]."
  [data-c split-method split-params]
  (vec
   (for
    [idx (range (n-splits split-method split-params))]
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
     :idx idx}))))


(def
 v56_l391
 (defn
  pocket-evaluate-pipelines
  "Evaluate pipelines across k-fold splits.\n  Like ml/evaluate-pipelines, but built on Cached references.\n  \n  `pipelines` is a seq of pipeline functions (from mm/pipeline).\n  `metric-fn` takes (test-ds, prediction-ds) and returns a number."
  [data-c split-method split-params pipelines metric-fn]
  (let
   [splits (pocket-splits data-c split-method split-params)]
   (vec
    (for
     [pipe-fn pipelines {:keys [train test idx]} splits]
     (let
      [fit-ctx
       (mm/fit-pipe train pipe-fn)
       transform-ctx
       (mm/transform-pipe test pipe-fn fit-ctx)
       metric
       (metric-fn
        @(:target transform-ctx)
        @(:metamorph/data transform-ctx))]
      {:split-idx idx, :metric metric, :model (:model fit-ctx)}))))))


(def
 v58_l415
 (defn
  rmse-metric
  "Compute RMSE between actual and predicted :y columns."
  [test-ds pred-ds]
  (loss/rmse (:y test-ds) (:y pred-ds))))


(def
 v59_l420
 (defn
  make-pipe
  [{:keys [feature-set model-spec]}]
  (mm/pipeline
   #:metamorph{:id :clip}
   (pocket-fitted c-fit-outlier-threshold c-clip-outliers)
   #:metamorph{:id :prep}
   (mm/lift c-prepare-features feature-set)
   #:metamorph{:id :model}
   (pocket-model model-spec))))


(def
 v60_l426
 (def
  configs
  [{:feature-set :poly+trig, :model-spec cart-spec}
   {:feature-set :raw, :model-spec cart-spec}
   {:feature-set :poly+trig, :model-spec linear-sgd-spec}]))


(def
 v61_l431
 (def
  results
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe configs)
   rmse-metric)))


(def v63_l438 (count results))


(deftest t64_l440 (is ((fn [n] (= n 9)) v63_l438)))


(def
 v66_l445
 (def
  summary
  (let
   [grouped (partition 3 results)]
   (mapv
    (fn
     [config rs]
     {:feature-set (:feature-set config),
      :model-type (-> config :model-spec :tribuo-trainer-name),
      :mean-rmse (tcc/mean (map :metric rs))})
    configs
    grouped))))


(def v67_l453 (tc/dataset summary))


(deftest t68_l455 (is ((fn [ds] (= 3 (tc/row-count ds))) v67_l453)))


(def
 v70_l460
 (def
  results-2
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe configs)
   rmse-metric)))


(def v71_l465 (= (mapv :metric results) (mapv :metric results-2)))


(deftest t72_l467 (is ((fn [eq] (true? eq)) v71_l465)))


(def v74_l477 (pocket/cleanup!))


(def
 v75_l479
 (def
  sweep-configs
  (vec
   (for
    [depth [4 6 8 12] fs [:raw :poly+trig]]
    {:feature-set fs,
     :model-spec
     {:model-type :scicloj.ml.tribuo/regression,
      :tribuo-components
      [{:name "cart",
        :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
        :properties {:maxDepth (str depth)}}],
      :tribuo-trainer-name "cart"}}))))


(def
 v76_l489
 (def
  sweep-results
  (pocket-evaluate-pipelines
   data-c
   :kfold
   {:k 3, :seed 42}
   (mapv make-pipe sweep-configs)
   rmse-metric)))


(def v78_l496 (count sweep-results))


(deftest t79_l498 (is ((fn [n] (= n 24)) v78_l496)))


(def
 v81_l503
 (def
  sweep-summary
  (let
   [grouped (partition 3 sweep-results)]
   (->>
    (mapv
     (fn
      [config rs]
      {:depth
       (->
        config
        :model-spec
        :tribuo-components
        first
        :properties
        :maxDepth),
       :feature-set (:feature-set config),
       :mean-rmse (tcc/mean (map :metric rs))})
     sweep-configs
     grouped)
    (sort-by :mean-rmse)))))


(def v82_l513 (tc/dataset sweep-summary))


(deftest t83_l515 (is ((fn [ds] (= 8 (tc/row-count ds))) v82_l513)))


(def
 v85_l530
 (pocket/origin-story-mermaid (:model (first sweep-results))))


(def v87_l596 (pocket/cleanup!))
