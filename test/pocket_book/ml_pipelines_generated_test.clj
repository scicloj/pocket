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


(def v18_l144 (pocket/cleanup!))


(def
 v19_l146
 (let
  [train-prep
   (prepare-features (:train splits) :poly+trig)
   test-prep
   (prepare-features (:test splits) :poly+trig)
   model
   @(pocket/cached #'ml/train train-prep cart-spec)]
  {:rmse (loss/rmse (:y test-prep) (:y (ml/predict test-prep model)))}))


(deftest t20_l151 (is ((fn [m] (< (:rmse m) 2.0)) v19_l146)))


(def
 v22_l190
 (def
  cart-pipeline
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (ml/model cart-spec))))


(def
 v24_l201
 (def fitted-ctx (mm/fit-pipe (:train splits) cart-pipeline)))


(def
 v26_l207
 (def
  predictions
  (:metamorph/data
   (mm/transform-pipe (:test splits) cart-pipeline fitted-ctx))))


(def v27_l211 (loss/rmse (:y (:test splits)) (:y predictions)))


(deftest t28_l213 (is ((fn [rmse] (< rmse 2.0)) v27_l211)))


(def
 v30_l224
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
 v31_l235
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


(def v32_l244 manual-results)


(deftest
 t33_l246
 (is ((fn [m] (< (:rmse (:cart-raw m)) 2.0)) v32_l244)))


(def
 v35_l260
 (def holdout-splits (tc/split->seq ds-500 :holdout {:seed 42})))


(def
 v37_l265
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
 v39_l276
 (mapv
  (fn
   [pipe-results]
   (let
    [r (first pipe-results)]
    {:rmse (get-in r [:test-transform :metric]),
     :fit-ms (:timing-fit r)}))
  eval-results))


(deftest
 t40_l282
 (is
  ((fn
    [rows]
    (every? (fn* [p1__93513#] (number? (:rmse p1__93513#))) rows))
   v39_l276)))


(def v42_l298 ml/train-predict-cache)


(def v44_l303 (def mm-cache (atom {})))


(def
 v45_l305
 (reset!
  ml/train-predict-cache
  {:use-cache true,
   :get-fn (fn [k] (get @mm-cache k)),
   :set-fn (fn [k v] (swap! mm-cache assoc k v))}))


(def
 v47_l312
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
 t48_l324
 (is
  ((fn
    [m]
    (and (= 1 (:cache-entries m)) (> (:first-ms m) (:second-ms m))))
   v47_l312)))


(def
 v50_l336
 (reset!
  ml/train-predict-cache
  {:use-cache false, :get-fn (fn [k] nil), :set-fn (fn [k v] nil)}))


(def
 v52_l359
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


(def v54_l392 (pocket/cleanup!))


(def
 v55_l394
 (def
  pocket-cart-pipe
  (mm/pipeline
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def
 v57_l401
 (def pocket-fitted (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def v59_l405 (pocket/cache-stats))


(deftest
 t60_l407
 (is ((fn [stats] (= 1 (:total-entries stats))) v59_l405)))


(def
 v62_l412
 (def pocket-fitted-2 (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def
 v64_l416
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


(deftest t65_l421 (is ((fn [m] (= (:rmse-1 m) (:rmse-2 m))) v64_l416)))


(def v67_l433 (pocket/cleanup!))


(def
 v68_l435
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
 v69_l444
 (let
  [r (first (first pocket-eval))]
  {:test-rmse (get-in r [:test-transform :metric])}))


(deftest t70_l447 (is ((fn [m] (< (:test-rmse m) 2.0)) v69_l444)))


(def v72_l466 (pocket/cleanup!))


(def v73_l468 (def depth-values [2 4 6 8 12]))


(def
 v74_l470
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
 v76_l488
 (def kfold-splits (tc/split->seq ds-500 :kfold {:k 3, :seed 42})))


(def
 v78_l493
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
 v79_l502
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


(def v80_l508 first-run-ms)


(def
 v82_l512
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


(def v83_l518 second-run-ms)


(deftest t84_l520 (is ((fn [ms] (< ms first-run-ms)) v83_l518)))


(def v86_l525 (pocket/cache-stats))


(deftest
 t87_l527
 (is ((fn [stats] (= 15 (:total-entries stats))) v86_l525)))


(def v89_l532 (def depth-results (run-depth-search)))


(def
 v90_l534
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


(def v91_l542 depth-summary)


(deftest
 t92_l544
 (is ((fn [rows] (= (count rows) (count depth-values))) v91_l542)))


(def v94_l556 (pocket/cleanup!))


(def
 v95_l558
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
 v96_l579
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
 v98_l590
 (let
  [best
   (first
    (first
     (sort-by
      (fn*
       [p1__93514#]
       (get-in (first p1__93514#) [:test-transform :metric]))
      search-results)))]
  {:best-rmse (get-in best [:test-transform :metric]),
   :best-fit-ms (:timing-fit best)}))


(deftest t99_l596 (is ((fn [m] (< (:best-rmse m) 2.0)) v98_l590)))


(def v101_l603 (pocket/cache-stats))


(def
 v103_l619
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
 v104_l632
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


(def v105_l655 (tc/dataset scaling-results))


(deftest
 t106_l657
 (is
  ((fn
    [ds]
    (let
     [row-10k (last (tc/rows ds :as-maps))]
     (< (:pocket-second-ms row-10k) (:uncached-ms row-10k))))
   v105_l655)))


(def
 v108_l685
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


(def v110_l731 (pocket/cleanup!))
