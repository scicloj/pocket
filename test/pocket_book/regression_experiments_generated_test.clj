(ns
 pocket-book.regression-experiments-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tech.v3.dataset :as ds]
  [tech.v3.dataset.modelling :as ds-mod]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [scicloj.tableplot.v1.plotly :as plotly]
  [clojure.test :refer [deftest is]]))


(def v2_l37 (def cache-dir "/tmp/pocket-regression"))


(def v3_l39 (pocket/set-base-cache-dir! cache-dir))


(def v4_l41 (pocket/cleanup!))


(def
 v6_l48
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Returns a dataset with columns `:x` and `:y`."
  [f n noise-sd seed]
  (let
   [rng
    (java.util.Random. (long seed))
    xs
    (vec (repeatedly n (fn* [] (* 10.0 (.nextDouble rng)))))
    ys
    (mapv
     (fn
      [x]
      (+ (double (f x)) (* (double noise-sd) (.nextGaussian rng))))
     xs)]
   (-> (tc/dataset {:x xs, :y ys}) (ds-mod/set-inference-target :y)))))


(def
 v7_l61
 (defn
  prepare-features
  "Add derived columns to a dataset according to `feature-set`.\n  Supported feature sets:\n  - `:raw`       — no extra columns\n  - `:quadratic` — add x²\n  - `:trig`      — add sin(x) and cos(x)\n  - `:poly+trig` — add x², sin(x), and cos(x)"
  [ds feature-set]
  (let
   [xv (vec (:x ds))]
   (->
    (case
     feature-set
     :raw
     ds
     :quadratic
     (->
      ds
      (tc/add-column
       :x2
       (mapv (fn* [p1__97853#] (* p1__97853# p1__97853#)) xv)))
     :trig
     (->
      ds
      (tc/add-column
       :sin-x
       (mapv (fn* [p1__97854#] (Math/sin p1__97854#)) xv))
      (tc/add-column
       :cos-x
       (mapv (fn* [p1__97855#] (Math/cos p1__97855#)) xv)))
     :poly+trig
     (->
      ds
      (tc/add-column
       :x2
       (mapv (fn* [p1__97856#] (* p1__97856# p1__97856#)) xv))
      (tc/add-column
       :sin-x
       (mapv (fn* [p1__97857#] (Math/sin p1__97857#)) xv))
      (tc/add-column
       :cos-x
       (mapv (fn* [p1__97858#] (Math/cos p1__97858#)) xv))))
    (ds-mod/set-inference-target :y)))))


(def
 v8_l83
 (defn
  train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v9_l88
 (defn
  predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let
   [pred (ml/predict test-ds model)]
   (loss/rmse (:y test-ds) (:y pred)))))


(def
 v11_l99
 (defn nonlinear-fn "y = sin(x) · x" [x] (* (Math/sin x) x)))


(def
 v13_l110
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
 v14_l121
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v16_l138
 (def
  data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 500 0.5 42)))


(def v17_l141 (tc/head data))


(def
 v19_l145
 (def split (first (tc/split->seq data :holdout {:seed 42}))))


(def v21_l150 (def feature-sets [:raw :quadratic :trig :poly+trig]))


(def
 v23_l157
 (def
  prepared
  (into
   {}
   (for
    [fs feature-sets role [:train :test]]
    [[fs role] @(pocket/cached #'prepare-features (role split) fs)]))))


(def
 v25_l168
 (def
  models
  (into
   {}
   (for
    [fs
     feature-sets
     [model-name spec]
     [[:sgd linear-sgd-spec] [:cart cart-spec]]]
    [[fs model-name]
     @(pocket/cached #'train-model (prepared [fs :train]) spec)]))))


(def
 v27_l180
 (def
  feature-results
  (vec
   (for
    [fs
     feature-sets
     [model-name _]
     [[:sgd linear-sgd-spec] [:cart cart-spec]]]
    {:feature-set fs,
     :model (name model-name),
     :rmse
     (predict-and-rmse
      (prepared [fs :test])
      (models [fs model-name]))}))))


(def v28_l189 feature-results)


(deftest
 t29_l191
 (is
  ((fn
    [rows]
    (let
     [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
     (and
      (> (m [:raw "sgd"]) 3.0)
      (< (m [:poly+trig "sgd"]) 2.0)
      (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))
   v28_l189)))


(def
 v31_l209
 (let
  [test-ds
   (prepared [:raw :test])
   sgd-pred
   (:y
    (ml/predict
     (prepared [:poly+trig :test])
     (models [:poly+trig :sgd])))
   cart-pred
   (:y (ml/predict test-ds (models [:raw :cart])))]
  (->
   (tc/dataset
    {:x (:x test-ds),
     :actual (:y test-ds),
     :Linear-SGD sgd-pred,
     :CART cart-pred})
   (plotly/layer-point
    {:=x :x,
     :=y :actual,
     :=name "actual",
     :=mark-opacity 0.3,
     :=mark-color "gray"})
   (plotly/layer-point
    {:=x :x,
     :=y :Linear-SGD,
     :=name "Linear SGD (poly+trig)",
     :=mark-opacity 0.5,
     :=mark-color "steelblue"})
   (plotly/layer-point
    {:=x :x,
     :=y :CART,
     :=name "CART (raw)",
     :=mark-opacity 0.5,
     :=mark-color "tomato"}))))


(def v33_l233 (def noise-levels [0.1 0.5 1.0 2.0 5.0]))


(def
 v34_l235
 (def
  noise-results
  (vec
   (for
    [noise-sd noise-levels]
    (let
     [ds
      @(pocket/cached
        #'make-regression-data
        #'nonlinear-fn
        500
        noise-sd
        42)
      sp
      (first (tc/split->seq ds :holdout {:seed 42}))
      cart-train
      @(pocket/cached #'prepare-features (:train sp) :raw)
      cart-test
      @(pocket/cached #'prepare-features (:test sp) :raw)
      sgd-train
      @(pocket/cached #'prepare-features (:train sp) :poly+trig)
      sgd-test
      @(pocket/cached #'prepare-features (:test sp) :poly+trig)
      cart-model
      @(pocket/cached #'train-model cart-train cart-spec)
      sgd-model
      @(pocket/cached #'train-model sgd-train linear-sgd-spec)]
     {:noise-sd noise-sd,
      :cart-rmse (predict-and-rmse cart-test cart-model),
      :sgd-rmse (predict-and-rmse sgd-test sgd-model)})))))


(def v35_l251 noise-results)


(deftest
 t36_l253
 (is
  ((fn
    [rows]
    (let
     [low
      (first
       (filter (fn* [p1__97859#] (= 0.1 (:noise-sd p1__97859#))) rows))
      high
      (first
       (filter
        (fn* [p1__97860#] (= 5.0 (:noise-sd p1__97860#)))
        rows))]
     (and
      (< (:cart-rmse low) (:sgd-rmse low))
      (> (:cart-rmse high) (:sgd-rmse high)))))
   v35_l251)))


(def
 v38_l267
 (let
  [rows
   (mapcat
    (fn
     [{:keys [noise-sd cart-rmse sgd-rmse]}]
     [{:noise-sd noise-sd, :model "CART", :rmse cart-rmse}
      {:noise-sd noise-sd, :model "Linear SGD", :rmse sgd-rmse}])
    noise-results)]
  (->
   (tc/dataset rows)
   (plotly/layer-line {:=x :noise-sd, :=y :rmse, :=color :model})
   (plotly/layer-point {:=x :noise-sd, :=y :rmse, :=color :model}))))


(def v40_l283 (:total-entries (pocket/cache-stats)))


(deftest t41_l285 (is ((fn [n] (> n 30)) v40_l283)))


(def v42_l288 (pocket/cache-entries))


(def v44_l304 (pocket/cleanup!))
