(ns
 pocket-book.ml-pipelines-generated-test
 (:require
  [pocket-book.logging]
  [scicloj.pocket :as pocket]
  [scicloj.kindly.v4.kind :as kind]
  [tablecloth.api :as tc]
  [tablecloth.column.api :as tcc]
  [tech.v3.datatype :as dtype]
  [tech.v3.dataset :as ds]
  [tech.v3.dataset.modelling :as ds-mod]
  [tech.v3.dataset.column-filters :as cf]
  [scicloj.metamorph.core :as mm]
  [scicloj.metamorph.ml :as ml]
  [scicloj.metamorph.ml.loss :as loss]
  [scicloj.ml.tribuo]
  [clojure.test :refer [deftest is]]))


(def v2_l52 (def cache-dir "/tmp/pocket-ml-pipelines"))


(def v3_l54 (pocket/set-base-cache-dir! cache-dir))


(def v4_l56 (pocket/cleanup!))


(def
 v6_l71
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Returns a dataset with columns `:x` and `:y`."
  [{:keys [f n noise-sd seed]}]
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
 v7_l84
 (defn
  nonlinear-fn
  "y = sin(x) · x — our ground truth."
  [x]
  (* (Math/sin x) x)))


(def
 v8_l89
 (defn
  prepare-features
  "Add derived columns based on `feature-set`:\n  `:raw` (no extras), `:poly+trig` (x², sin(x), cos(x))."
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
 v10_l103
 (def
  linear-sgd-spec
  "Linear regression via stochastic gradient descent."
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
 v11_l115
 (def
  cart-spec
  "CART regression tree with max depth 8."
  {:model-type :scicloj.ml.tribuo/regression,
   :tribuo-components
   [{:name "cart",
     :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
     :properties {:maxDepth "8"}}],
   :tribuo-trainer-name "cart"}))


(def
 v13_l125
 (def
  ds-500
  (make-regression-data
   {:f nonlinear-fn, :n 500, :noise-sd 0.5, :seed 42})))


(def
 v14_l127
 (def splits (first (tc/split->seq ds-500 :holdout {:seed 42}))))


(def
 v15_l129
 (let
  [train-prep
   (prepare-features (:train splits) :poly+trig)
   test-prep
   (prepare-features (:test splits) :poly+trig)
   model
   (ml/train train-prep cart-spec)]
  {:rmse (loss/rmse (:y test-prep) (:y (ml/predict test-prep model)))}))


(deftest t16_l134 (is ((fn [m] (< (:rmse m) 2.0)) v15_l129)))


(def
 v18_l167
 (def
  cart-pipeline
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (ml/model cart-spec))))


(def
 v20_l178
 (def fitted-ctx (mm/fit-pipe (:train splits) cart-pipeline)))


(def
 v22_l184
 (def
  predictions
  (:metamorph/data
   (mm/transform-pipe (:test splits) cart-pipeline fitted-ctx))))


(def v23_l188 (loss/rmse (:y (:test splits)) (:y predictions)))


(deftest t24_l190 (is ((fn [rmse] (< rmse 2.0)) v23_l188)))


(def
 v26_l201
 (def
  pipe-fns
  {:cart-raw
   (mm/pipeline
    (mm/lift prepare-features :raw)
    #:metamorph{:id :model}
    (ml/model cart-spec)),
   :cart-poly
   (mm/pipeline
    (mm/lift prepare-features :poly+trig)
    #:metamorph{:id :model}
    (ml/model cart-spec)),
   :sgd-poly
   (mm/pipeline
    (mm/lift prepare-features :poly+trig)
    #:metamorph{:id :model}
    (ml/model linear-sgd-spec))}))


(def
 v27_l212
 (def
  manual-results
  (into
   {}
   (for
    [[pipe-name pipe-fn] pipe-fns]
    (let
     [fitted
      (mm/fit-pipe (:train splits) pipe-fn)
      pred-ds
      (:metamorph/data
       (mm/transform-pipe (:test splits) pipe-fn fitted))]
     [pipe-name
      {:rmse (loss/rmse (:y (:test splits)) (:y pred-ds))}])))))


(def v28_l221 manual-results)


(deftest
 t29_l223
 (is ((fn [m] (< (:rmse (:cart-raw m)) 2.0)) v28_l221)))


(def
 v31_l237
 (def holdout-splits (tc/split->seq ds-500 :holdout {:seed 42})))


(def
 v33_l242
 (def
  eval-results
  (ml/evaluate-pipelines
   (vals pipe-fns)
   holdout-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false,
    :return-best-crossvalidation-only false})))


(def
 v35_l253
 (mapv
  (fn
   [pipe-results]
   (let
    [r (first pipe-results)]
    {:rmse (get-in r [:test-transform :metric]),
     :fit-ms (:timing-fit r)}))
  eval-results))


(deftest
 t36_l259
 (is
  ((fn
    [rows]
    (every? (fn* [p1__92243#] (number? (:rmse p1__92243#))) rows))
   v35_l253)))


(def v38_l275 ml/train-predict-cache)


(def v40_l280 (def mm-cache (atom {})))


(def
 v41_l282
 (reset!
  ml/train-predict-cache
  {:use-cache true,
   :get-fn (fn [k] (get @mm-cache k)),
   :set-fn (fn [k v] (swap! mm-cache assoc k v))}))


(def
 v43_l289
 (let
  [train-prep (prepare-features (:train splits) :poly+trig)]
  (let
   [start
    (System/nanoTime)
    _
    (ml/train train-prep cart-spec)
    first-ms
    (/ (- (System/nanoTime) start) 1000000.0)
    start2
    (System/nanoTime)
    _
    (ml/train train-prep cart-spec)
    second-ms
    (/ (- (System/nanoTime) start2) 1000000.0)]
   {:first-ms (Math/round first-ms),
    :second-ms (Math/round second-ms),
    :cache-entries (count @mm-cache)})))


(deftest
 t44_l301
 (is
  ((fn
    [m]
    (and (= 1 (:cache-entries m)) (> (:first-ms m) (:second-ms m))))
   v43_l289)))


(def
 v46_l319
 (reset!
  ml/train-predict-cache
  {:use-cache false, :get-fn (fn [k] nil), :set-fn (fn [k v] nil)}))


(def
 v48_l339
 (defn
  pocket-model
  "Like `ml/model`, but caches `ml/train` calls through Pocket.\n   Drop-in replacement for `ml/model` in metamorph pipelines.\n\n   In `:fit` mode, wraps `ml/train` with `pocket/cached` — the trained\n   model is persisted to disk, keyed by the dataset content and options.\n   In `:transform` mode, calls `ml/predict` directly (predictions are\n   cheap and dataset-dependent, so caching them is usually not worth it)."
  [options]
  (fn
   [{:metamorph/keys [id data mode], :as ctx}]
   (case
    mode
    :fit
    (let
     [model (deref (pocket/cached #'ml/train data options))]
     (assoc
      ctx
      id
      (assoc model :scicloj.metamorph.ml/unsupervised? false)))
    :transform
    (->
     ctx
     (update
      id
      assoc
      :scicloj.metamorph.ml/feature-ds
      (cf/feature data)
      :scicloj.metamorph.ml/target-ds
      (cf/target data))
     (assoc :metamorph/data (ml/predict data (get ctx id))))))))


(def v50_l372 (pocket/cleanup!))


(def
 v51_l374
 (def
  pocket-cart-pipe
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def
 v53_l381
 (def pocket-fitted (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def v55_l385 (pocket/cache-stats))


(deftest
 t56_l387
 (is ((fn [stats] (= 1 (:total-entries stats))) v55_l385)))


(def
 v58_l392
 (def pocket-fitted-2 (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def
 v60_l396
 (let
  [pred1
   (:metamorph/data
    (mm/transform-pipe (:test splits) pocket-cart-pipe pocket-fitted))
   pred2
   (:metamorph/data
    (mm/transform-pipe
     (:test splits)
     pocket-cart-pipe
     pocket-fitted-2))]
  {:rmse-1 (loss/rmse (:y (:test splits)) (:y pred1)),
   :rmse-2 (loss/rmse (:y (:test splits)) (:y pred2))}))


(deftest t61_l401 (is ((fn [m] (= (:rmse-1 m) (:rmse-2 m))) v60_l396)))


(def v63_l413 (pocket/cleanup!))


(def
 v64_l415
 (def
  pocket-eval
  (ml/evaluate-pipelines
   [pocket-cart-pipe]
   holdout-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false,
    :return-best-crossvalidation-only false})))


(def
 v65_l424
 (let
  [r (first (first pocket-eval))]
  {:test-rmse (get-in r [:test-transform :metric])}))


(deftest t66_l427 (is ((fn [m] (< (:test-rmse m) 2.0)) v65_l424)))


(def v68_l446 (pocket/cleanup!))


(def v69_l448 (def depth-values [2 4 6 8 12]))


(def
 v70_l450
 (def
  depth-pipe-fns
  (vec
   (for
    [depth depth-values]
    (let
     [spec
      {:model-type :scicloj.ml.tribuo/regression,
       :tribuo-components
       [{:name "cart",
         :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
         :properties {:maxDepth (str depth)}}],
       :tribuo-trainer-name "cart"}]
     (mm/pipeline
      (mm/lift prepare-features :poly+trig)
      #:metamorph{:id :model}
      (pocket-model spec)))))))


(def
 v72_l468
 (def kfold-splits (tc/split->seq ds-500 :kfold {:k 3, :seed 42})))


(def
 v74_l473
 (defn
  run-depth-search
  []
  (ml/evaluate-pipelines
   depth-pipe-fns
   kfold-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false,
    :return-best-crossvalidation-only true})))


(def
 v75_l482
 (def
  first-run-ms
  (let
   [start
    (System/nanoTime)
    _
    (run-depth-search)
    elapsed
    (/ (- (System/nanoTime) start) 1000000.0)]
   (Math/round elapsed))))


(def v76_l488 first-run-ms)


(def
 v78_l492
 (def
  second-run-ms
  (let
   [start
    (System/nanoTime)
    _
    (run-depth-search)
    elapsed
    (/ (- (System/nanoTime) start) 1000000.0)]
   (Math/round elapsed))))


(def v79_l498 second-run-ms)


(deftest t80_l500 (is ((fn [ms] (< ms first-run-ms)) v79_l498)))


(def v82_l505 (pocket/cache-stats))


(deftest
 t83_l507
 (is ((fn [stats] (= 15 (:total-entries stats))) v82_l505)))


(def v85_l512 (def depth-results (run-depth-search)))


(def
 v86_l514
 (def
  depth-summary
  (mapv
   (fn
    [pipe-results depth]
    {:depth depth,
     :test-rmse
     (get-in (first pipe-results) [:test-transform :metric])})
   depth-results
   depth-values)))


(def v87_l522 depth-summary)


(deftest
 t88_l524
 (is ((fn [rows] (= (count rows) (count depth-values))) v87_l522)))


(def v90_l536 (pocket/cleanup!))


(def
 v91_l538
 (def
  search-pipe-fns
  (vec
   (concat
    (for
     [depth [4 6 8] fs [:raw :poly+trig]]
     (let
      [spec
       {:model-type :scicloj.ml.tribuo/regression,
        :tribuo-components
        [{:name "cart",
          :type "org.tribuo.regression.rtree.CARTRegressionTrainer",
          :properties {:maxDepth (str depth)}}],
        :tribuo-trainer-name "cart"}]
      (mm/pipeline
       (mm/lift prepare-features fs)
       #:metamorph{:id :model}
       (pocket-model spec))))
    (for
     [_depth [4 6 8]]
     (mm/pipeline
      (mm/lift prepare-features :poly+trig)
      #:metamorph{:id :model}
      (pocket-model linear-sgd-spec)))))))


(def
 v92_l559
 (def
  search-results
  (ml/evaluate-pipelines
   search-pipe-fns
   kfold-splits
   loss/rmse
   :loss
   {:return-best-pipeline-only false,
    :return-best-crossvalidation-only true})))


(def
 v94_l570
 (let
  [best
   (first
    (first
     (sort-by
      (fn*
       [p1__92244#]
       (get-in (first p1__92244#) [:test-transform :metric]))
      search-results)))]
  {:best-rmse (get-in best [:test-transform :metric]),
   :best-fit-ms (:timing-fit best)}))


(deftest t95_l576 (is ((fn [m] (< (:best-rmse m) 2.0)) v94_l570)))


(def v97_l583 (pocket/cache-stats))


(def
 v99_l599
 (defn
  time-pipeline
  "Time a 3-fold CV evaluation of a single pipeline.\n   Returns elapsed milliseconds."
  [pipe-fn data]
  (let
   [start
    (System/nanoTime)
    _
    (ml/evaluate-pipelines
     [pipe-fn]
     (tc/split->seq data :kfold {:k 3, :seed 42})
     loss/rmse
     :loss
     {:return-best-pipeline-only false})
    elapsed
    (/ (- (System/nanoTime) start) 1000000.0)]
   (Math/round elapsed))))


(def
 v100_l612
 (def
  scaling-results
  (vec
   (for
    [n [500 5000 10000]]
    (let
     [data
      (make-regression-data
       {:f nonlinear-fn, :n n, :noise-sd 0.5, :seed 42})
      uncached-pipe
      (mm/pipeline
       (mm/lift prepare-features :poly+trig)
       #:metamorph{:id :model}
       (ml/model cart-spec))
      cached-pipe
      (mm/pipeline
       (mm/lift prepare-features :poly+trig)
       #:metamorph{:id :model}
       (pocket-model cart-spec))]
     (pocket/cleanup!)
     (let
      [uncached-ms
       (time-pipeline uncached-pipe data)
       first-ms
       (time-pipeline cached-pipe data)
       second-ms
       (time-pipeline cached-pipe data)]
      {:n n,
       :uncached-ms uncached-ms,
       :pocket-first-ms first-ms,
       :pocket-second-ms second-ms}))))))


(def v101_l635 (tc/dataset scaling-results))


(deftest
 t102_l637
 (is
  ((fn
    [ds]
    (let
     [row-10k (last (tc/rows ds :as-maps))]
     (< (:pocket-second-ms row-10k) (:uncached-ms row-10k))))
   v101_l635)))


(def
 v104_l661
 (let
  [rows scaling-results]
  (kind/plotly
   {:data
    [{:x (mapv :n rows),
      :y (mapv :uncached-ms rows),
      :mode "lines+markers",
      :name "Uncached (ml/model)"}
     {:x (mapv :n rows),
      :y (mapv :pocket-first-ms rows),
      :mode "lines+markers",
      :name "Pocket (first run)"}
     {:x (mapv :n rows),
      :y (mapv :pocket-second-ms rows),
      :mode "lines+markers",
      :name "Pocket (second run)"}],
    :layout
    {:xaxis {:title "Data size (n)"},
     :yaxis {:title "Time (ms)"},
     :title "3-fold CV timing by data size"}})))


(def v106_l705 (pocket/cleanup!))
