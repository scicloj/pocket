(ns
 pocket-book.ml-workflows-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.dataset :as ds]
  [tech.v3.dataset.modelling :as ds-mod]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v2_l66 (def cache-dir "/tmp/pocket-regression"))


(def v3_l68 (pocket/set-base-cache-dir! cache-dir))


(def v4_l70 (pocket/cleanup!))


(def
 v6_l82
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Optional `outlier-fraction` (0–1) and `outlier-scale` inject\n  corrupted y values to simulate measurement errors."
  [{:keys [f n noise-sd seed outlier-fraction outlier-scale],
    :or {outlier-fraction 0, outlier-scale 10}}]
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
     xs)
    ys-final
    (if
     (pos? outlier-fraction)
     (let
      [out-rng (java.util.Random. (+ (long seed) 7919))]
      (mapv
       (fn
        [y]
        (if
         (< (.nextDouble out-rng) outlier-fraction)
         (+ y (* (double outlier-scale) (.nextGaussian out-rng)))
         y))
       ys))
     ys)]
   (->
    (tc/dataset {:x xs, :y ys-final})
    (ds-mod/set-inference-target :y)))))


(def
 v8_l110
 (defn
  split-dataset
  "Split a dataset into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v10_l121
 (defn
  prepare-features
  "Add derived columns to a dataset according to `feature-set`.\n  Supported feature sets:\n  - `:raw`       — no extra columns\n  - `:quadratic` — add x²\n  - `:trig`      — add sin(x) and cos(x)\n  - `:poly+trig` — add x², sin(x), and cos(x)"
  [ds feature-set]
  (let
   [x (:x ds)]
   (->
    (case
     feature-set
     :raw
     ds
     :quadratic
     (tc/add-columns ds {:x2 (tcc/sq x)})
     :trig
     (tc/add-columns ds {:sin-x (tcc/sin x), :cos-x (tcc/cos x)})
     :poly+trig
     (tc/add-columns
      ds
      {:x2 (tcc/sq x), :sin-x (tcc/sin x), :cos-x (tcc/cos x)}))
    (ds-mod/set-inference-target :y)))))


(def
 v12_l146
 (defn
  train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec)))


(def
 v13_l151
 (defn
  predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let
   [pred (ml/predict test-ds model)]
   (loss/rmse (:y test-ds) (:y pred)))))


(def
 v15_l167
 (defn nonlinear-fn "y = sin(x) · x" [x] (* (Math/sin x) x)))


(def
 v17_l187
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
 v18_l198
 (def
  cart-spec
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v20_l225
 (def
  data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn, :n 500, :noise-sd 0.5, :seed 42})))


(def v21_l229 (tc/head (deref data-c)))


(def
 v23_l233
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v25_l240 (def train-c (pocket/cached :train split-c)))


(def v26_l241 (def test-c (pocket/cached :test split-c)))


(def v28_l245 (def feature-sets [:raw :quadratic :trig :poly+trig]))


(def
 v30_l252
 (def
  prepared
  (into
   {}
   (for
    [fs feature-sets [role ds-c] [[:train train-c] [:test test-c]]]
    [[fs role] (pocket/cached #'prepare-features ds-c fs)]))))


(def
 v32_l263
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
     (pocket/cached #'train-model (prepared [fs :train]) spec)]))))


(def
 v34_l275
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
      @(prepared [fs :test])
      @(models [fs model-name]))}))))


(def v35_l284 feature-results)


(deftest
 t36_l286
 (is
  ((fn
    [rows]
    (let
     [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
     (and
      (> (m [:raw "sgd"]) 3.0)
      (< (m [:poly+trig "sgd"]) 2.0)
      (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))
   v35_l284)))


(def
 v38_l311
 (let
  [test-ds
   @(prepared [:raw :test])
   sgd-pred
   (:y
    (ml/predict
     @(prepared [:poly+trig :test])
     @(models [:poly+trig :sgd])))
   cart-pred
   (:y (ml/predict test-ds @(models [:raw :cart])))
   xs
   (vec (:x test-ds))
   actuals
   (vec (:y test-ds))
   sgd-vals
   (vec sgd-pred)
   cart-vals
   (vec cart-pred)]
  (kind/plotly
   {:data
    [{:x xs,
      :y actuals,
      :mode "markers",
      :name "actual",
      :marker {:opacity 0.3, :color "gray"}}
     {:x xs,
      :y sgd-vals,
      :mode "markers",
      :name "Linear SGD (poly+trig)",
      :marker {:opacity 0.5, :color "steelblue"}}
     {:x xs,
      :y cart-vals,
      :mode "markers",
      :name "CART (raw)",
      :marker {:opacity 0.5, :color "tomato"}}],
    :layout {:xaxis {:title "x"}, :yaxis {:title "y"}}})))


(def v40_l342 (def noise-levels [0.1 0.5 1.0 2.0 5.0]))


(def
 v41_l344
 (def
  noise-results
  (vec
   (for
    [noise-sd noise-levels]
    (let
     [data-c
      (pocket/cached
       #'make-regression-data
       {:f #'nonlinear-fn, :n 500, :noise-sd noise-sd, :seed 42})
      split-c
      (pocket/cached #'split-dataset data-c {:seed 42})
      train-c
      (pocket/cached :train split-c)
      test-c
      (pocket/cached :test split-c)
      cart-train
      (pocket/cached #'prepare-features train-c :raw)
      cart-test
      (pocket/cached #'prepare-features test-c :raw)
      sgd-train
      (pocket/cached #'prepare-features train-c :poly+trig)
      sgd-test
      (pocket/cached #'prepare-features test-c :poly+trig)
      cart-model
      (pocket/cached #'train-model cart-train cart-spec)
      sgd-model
      (pocket/cached #'train-model sgd-train linear-sgd-spec)]
     {:noise-sd noise-sd,
      :cart-rmse (predict-and-rmse @cart-test @cart-model),
      :sgd-rmse (predict-and-rmse @sgd-test @sgd-model)})))))


(def v42_l362 noise-results)


(deftest
 t43_l364
 (is
  ((fn
    [rows]
    (let
     [low
      (first
       (filter (fn* [p1__97968#] (= 0.1 (:noise-sd p1__97968#))) rows))
      high
      (first
       (filter
        (fn* [p1__97969#] (= 5.0 (:noise-sd p1__97969#)))
        rows))]
     (and
      (< (:cart-rmse low) (:sgd-rmse low))
      (> (:cart-rmse high) (:sgd-rmse high)))))
   v42_l362)))


(def
 v45_l386
 (let
  [noise-sds
   (vec (map :noise-sd noise-results))
   cart-rmses
   (vec (map :cart-rmse noise-results))
   sgd-rmses
   (vec (map :sgd-rmse noise-results))]
  (kind/plotly
   {:data
    [{:x noise-sds, :y cart-rmses, :mode "lines+markers", :name "CART"}
     {:x noise-sds,
      :y sgd-rmses,
      :mode "lines+markers",
      :name "Linear SGD"}],
    :layout {:xaxis {:title "noise-sd"}, :yaxis {:title "rmse"}}})))


(def v47_l402 (:total-entries (pocket/cache-stats)))


(deftest t48_l404 (is ((fn [n] (> n 30)) v47_l402)))


(def v49_l407 (pocket/cache-entries))


(def v51_l425 (pocket/cleanup!))


(def
 v53_l476
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :y from training data.\n  Returns {:lower <bound> :upper <bound>}."
  [train-ds]
  (println "  Fitting outlier threshold from training data...")
  (let
   [ys
    (sort (vec (:y train-ds)))
    n
    (count ys)
    q1
    (nth ys (int (* 0.25 n)))
    q3
    (nth ys (int (* 0.75 n)))
    iqr
    (- q3 q1)]
   {:lower (- q1 (* 1.5 iqr)), :upper (+ q3 (* 1.5 iqr))})))


(def
 v54_l489
 (defn
  clip-outliers
  "Clip :y values using pre-computed threshold bounds."
  [ds threshold]
  (println
   "  Clipping outliers with bounds:"
   (select-keys threshold [:lower :upper]))
  (let
   [{:keys [lower upper]} threshold]
   (->
    (tc/add-column ds :y (-> (:y ds) (tcc/max lower) (tcc/min upper)))
    (ds-mod/set-inference-target :y)))))


(def
 v55_l497
 (defn
  evaluate-model
  "Evaluate a model on test data."
  [test-ds model]
  (println "  Evaluating model...")
  (let
   [pred (ml/predict test-ds model)]
   {:rmse (loss/rmse (:y test-ds) (:y pred))})))


(def
 v57_l516
 (def
  c-fit-threshold
  (pocket/caching-fn #'fit-outlier-threshold {:storage :mem})))


(def
 v58_l519
 (def c-clip (pocket/caching-fn #'clip-outliers {:storage :mem})))


(def v59_l522 (def c-train (pocket/caching-fn #'train-model)))


(def
 v60_l525
 (def c-evaluate (pocket/caching-fn #'evaluate-model {:storage :none})))


(def
 v62_l532
 (def
  dag-data-c
  (pocket/cached
   #'make-regression-data
   {:f #'nonlinear-fn,
    :n 200,
    :noise-sd 0.3,
    :seed 99,
    :outlier-fraction 0.1,
    :outlier-scale 15})))


(def
 v63_l537
 (def
  dag-split-c
  (pocket/cached #'split-dataset dag-data-c {:seed 99})))


(def v64_l540 (def dag-train-c (pocket/cached :train dag-split-c)))


(def v65_l541 (def dag-test-c (pocket/cached :test dag-split-c)))


(def v67_l547 (def threshold-c (c-fit-threshold dag-train-c)))


(def v68_l550 (def train-clipped-c (c-clip dag-train-c threshold-c)))


(def v69_l553 (def test-clipped-c (c-clip dag-test-c threshold-c)))


(def v70_l556 (def model-c (c-train train-clipped-c cart-spec)))


(def v71_l559 (def metrics-c (c-evaluate test-clipped-c model-c)))


(def v73_l573 (pocket/origin-story metrics-c))


(def v75_l581 (pocket/origin-story-graph metrics-c))


(def v77_l588 (pocket/origin-story-mermaid metrics-c))


(def v79_l592 (deref metrics-c))


(deftest
 t80_l594
 (is ((fn [m] (and (map? m) (contains? m :rmse))) v79_l592)))


(def
 v82_l609
 (defn
  run-pipeline
  "Run a complete pipeline with given hyperparameters."
  [{:keys [noise-sd feature-set max-depth]}]
  (let
   [ds
    (make-regression-data
     {:f nonlinear-fn, :n 200, :noise-sd noise-sd, :seed 42})
    sp
    (split-dataset ds {:seed 42})
    train-prep
    (prepare-features (:train sp) feature-set)
    test-prep
    (prepare-features (:test sp) feature-set)
    spec
    {:model-type :scicloj.ml.tribuo/regression,
     :tribuo-components
     [{:name "cart",
       :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
       :properties {:maxDepth (str max-depth)}}],
     :tribuo-trainer-name "cart"}
    model
    (ml/train train-prep spec)
    pred
    (ml/predict test-prep model)]
   {:rmse (loss/rmse (:y test-prep) (:y pred))})))


(def
 v84_l627
 (def
  experiments
  (for
   [noise-sd [0.3 0.5] feature-set [:raw :poly+trig] max-depth [4 8]]
   (pocket/cached
    #'run-pipeline
    {:noise-sd noise-sd,
     :feature-set feature-set,
     :max-depth max-depth}))))


(def v86_l638 (def comparison (pocket/compare-experiments experiments)))


(def v87_l641 (tc/dataset comparison))


(deftest
 t88_l643
 (is
  ((fn
    [ds]
    (and
     (= 8 (tc/row-count ds))
     (some #{:noise-sd} (tc/column-names ds))
     (some #{:feature-set} (tc/column-names ds))
     (some #{:max-depth} (tc/column-names ds))))
   v87_l641)))


(def
 v90_l656
 (let
  [rows
   (map
    (fn
     [exp]
     (merge
      (select-keys exp [:noise-sd :feature-set :max-depth])
      (:result exp)))
    comparison)
   grouped
   (group-by (juxt :feature-set :noise-sd) rows)
   feature-colors
   {:raw "steelblue", :poly "tomato", :poly+trig "green"}]
  (kind/plotly
   {:data
    (vec
     (for
      [[[feature-set noise-sd] pts]
       (sort-by first grouped)
       :let
       [max-depths (mapv :max-depth pts) rmses (mapv :rmse pts)]]
      {:x max-depths,
       :y rmses,
       :mode "markers",
       :name (str (name feature-set) " (noise=" noise-sd ")"),
       :legendgroup (name feature-set),
       :marker
       {:size (+ 8 (* 15 noise-sd)),
        :color (feature-colors feature-set)}})),
    :layout {:xaxis {:title "max-depth"}, :yaxis {:title "rmse"}}})))


(def v92_l709 (pocket/cleanup!))
