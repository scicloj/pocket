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


(def v2_l53 (def cache-dir "/tmp/pocket-ml-pipelines"))


(def v3_l55 (pocket/set-base-cache-dir! cache-dir))


(def v4_l57 (pocket/cleanup!))


(def
 v6_l72
 (defn
  make-regression-data
  "Generate a synthetic regression dataset.\n  `f` is a function from x to y (the ground truth).\n  Optional `outlier-fraction` (0–1) and `outlier-scale` inject\n  corrupted x values to simulate sensor glitches."
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


(def
 v7_l95
 (defn
  nonlinear-fn
  "y = sin(x) · x — our ground truth."
  [x]
  (* (Math/sin x) x)))


(def
 v8_l100
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
 v10_l125
 (defn
  fit-outlier-threshold
  "Compute IQR-based clipping bounds for :x from training data.\n  Returns {:lower <bound> :upper <bound>}."
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
 v11_l137
 (defn
  clip-outliers
  "Clip :x values using pre-computed threshold bounds."
  [ds threshold]
  (let
   [{:keys [lower upper]} threshold]
   (tc/add-column ds :x (-> (:x ds) (tcc/max lower) (tcc/min upper))))))


(def
 v12_l143
 (defn
  split-dataset
  "Split a dataset into train/test using holdout."
  [ds {:keys [seed]}]
  (first (tc/split->seq ds :holdout {:seed seed}))))


(def
 v14_l150
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
 v15_l162
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
 v17_l172
 (def
  ds-500
  (make-regression-data
   {:f nonlinear-fn,
    :n 500,
    :noise-sd 0.5,
    :seed 42,
    :outlier-fraction 0.1,
    :outlier-scale 15})))


(def
 v18_l175
 (def splits (first (tc/split->seq ds-500 :holdout {:seed 42}))))


(def
 v20_l183
 (let
  [threshold
   (fit-outlier-threshold (:train splits))
   train-clipped
   (clip-outliers (:train splits) threshold)
   train-prep
   (prepare-features train-clipped :poly+trig)
   test-clipped
   (clip-outliers (:test splits) threshold)
   test-prep
   (prepare-features test-clipped :poly+trig)
   model
   (ml/train train-prep cart-spec)]
  {:rmse (loss/rmse (:y test-prep) (:y (ml/predict test-prep model)))}))


(deftest t21_l191 (is ((fn [m] (< (:rmse m) 10.0)) v20_l183)))


(def v23_l202 (pocket/cleanup!))


(def
 v24_l204
 (let
  [threshold
   (fit-outlier-threshold (:train splits))
   train-clipped
   (clip-outliers (:train splits) threshold)
   train-prep
   (prepare-features train-clipped :poly+trig)
   test-clipped
   (clip-outliers (:test splits) threshold)
   test-prep
   (prepare-features test-clipped :poly+trig)
   model
   @(pocket/cached #'ml/train train-prep cart-spec)]
  {:rmse (loss/rmse (:y test-prep) (:y (ml/predict test-prep model)))}))


(deftest t25_l212 (is ((fn [m] (< (:rmse m) 10.0)) v24_l204)))


(def
 v27_l256
 (defn
  clip-outlier-step
  "Pipeline step: fit outlier threshold in :fit mode, apply stored bounds in :transform."
  []
  (fn
   [{:metamorph/keys [data mode id], :as ctx}]
   (case
    mode
    :fit
    (let
     [threshold (fit-outlier-threshold data)]
     (assoc
      ctx
      id
      threshold
      :metamorph/data
      (clip-outliers data threshold)))
    :transform
    (assoc ctx :metamorph/data (clip-outliers data (get ctx id)))))))


(def
 v28_l265
 (def
  cart-pipeline
  (mm/pipeline
   #:metamorph{:id :clip-outlier}
   (clip-outlier-step)
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (ml/model cart-spec))))


(def
 v30_l278
 (def fitted-ctx (mm/fit-pipe (:train splits) cart-pipeline)))


(def
 v32_l284
 (def
  predictions
  (:metamorph/data
   (mm/transform-pipe (:test splits) cart-pipeline fitted-ctx))))


(def v33_l288 (loss/rmse (:y (:test splits)) (:y predictions)))


(deftest t34_l290 (is ((fn [rmse] (< rmse 10.0)) v33_l288)))


(def
 v36_l301
 (def
  pipe-fns
  {:cart-raw
   (mm/pipeline
    #:metamorph{:id :clip-outlier}
    (clip-outlier-step)
    (mm/lift prepare-features :raw)
    #:metamorph{:id :model}
    (ml/model cart-spec)),
   :cart-poly
   (mm/pipeline
    #:metamorph{:id :clip-outlier}
    (clip-outlier-step)
    (mm/lift prepare-features :poly+trig)
    #:metamorph{:id :model}
    (ml/model cart-spec)),
   :sgd-poly
   (mm/pipeline
    #:metamorph{:id :clip-outlier}
    (clip-outlier-step)
    (mm/lift prepare-features :poly+trig)
    #:metamorph{:id :model}
    (ml/model linear-sgd-spec))}))


(def
 v37_l315
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


(def v38_l324 manual-results)


(deftest
 t39_l326
 (is ((fn [m] (< (:rmse (:cart-raw m)) 10.0)) v38_l324)))


(def
 v41_l340
 (def holdout-splits (tc/split->seq ds-500 :holdout {:seed 42})))


(def
 v43_l345
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
 v45_l356
 (mapv
  (fn
   [pipe-results]
   (let
    [r (first pipe-results)]
    {:rmse (get-in r [:test-transform :metric]),
     :fit-ms (:timing-fit r)}))
  eval-results))


(deftest
 t46_l362
 (is
  ((fn
    [rows]
    (every? (fn* [p1__101868#] (number? (:rmse p1__101868#))) rows))
   v45_l356)))


(def v48_l378 ml/train-predict-cache)


(def v50_l383 (def mm-cache (atom {})))


(def
 v51_l385
 (reset!
  ml/train-predict-cache
  {:use-cache true,
   :get-fn (fn [k] (get @mm-cache k)),
   :set-fn (fn [k v] (swap! mm-cache assoc k v))}))


(def
 v53_l392
 (let
  [threshold
   (fit-outlier-threshold (:train splits))
   train-clipped
   (clip-outliers (:train splits) threshold)
   train-prep
   (prepare-features train-clipped :poly+trig)]
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
 t54_l406
 (is
  ((fn
    [m]
    (and (= 1 (:cache-entries m)) (> (:first-ms m) (:second-ms m))))
   v53_l392)))


(def
 v56_l418
 (reset!
  ml/train-predict-cache
  {:use-cache false, :get-fn (fn [k] nil), :set-fn (fn [k v] nil)}))


(def
 v58_l441
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


(def v60_l474 (pocket/cleanup!))


(def
 v61_l476
 (def
  pocket-cart-pipe
  (mm/pipeline
   #:metamorph{:id :clip-outlier}
   (clip-outlier-step)
   (mm/lift prepare-features :poly+trig)
   #:metamorph{:id :model}
   (pocket-model cart-spec))))


(def
 v63_l484
 (def pocket-fitted (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def v65_l488 (pocket/cache-stats))


(deftest
 t66_l490
 (is ((fn [stats] (= 1 (:total-entries stats))) v65_l488)))


(def
 v68_l495
 (def pocket-fitted-2 (mm/fit-pipe (:train splits) pocket-cart-pipe)))


(def
 v70_l499
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


(deftest t71_l504 (is ((fn [m] (= (:rmse-1 m) (:rmse-2 m))) v70_l499)))


(def v73_l516 (pocket/cleanup!))


(def
 v74_l518
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
 v75_l527
 (let
  [r (first (first pocket-eval))]
  {:test-rmse (get-in r [:test-transform :metric])}))


(deftest t76_l530 (is ((fn [m] (< (:test-rmse m) 10.0)) v75_l527)))


(def v78_l556 (pocket/cleanup!))


(def
 v79_l558
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
 v80_l563
 (def split-c (pocket/cached #'split-dataset data-c {:seed 42})))


(def v81_l564 (def train-c (pocket/cached :train split-c)))


(def v82_l565 (def test-c (pocket/cached :test split-c)))


(def
 v83_l567
 (def threshold-c (pocket/cached #'fit-outlier-threshold train-c)))


(def
 v84_l568
 (def
  train-clipped-c
  (pocket/cached #'clip-outliers train-c threshold-c)))


(def
 v85_l569
 (def
  test-clipped-c
  (pocket/cached #'clip-outliers test-c threshold-c)))


(def
 v86_l570
 (def
  train-prepped-c
  (pocket/cached #'prepare-features train-clipped-c :poly+trig)))


(def
 v87_l571
 (def
  test-prepped-c
  (pocket/cached #'prepare-features test-clipped-c :poly+trig)))


(def
 v88_l572
 (def model-c (pocket/cached #'ml/train train-prepped-c cart-spec)))


(def v90_l581 (pocket/origin-story-mermaid model-c))


(def
 v92_l593
 (let
  [test-prepped @test-prepped-c]
  {:rmse
   (loss/rmse
    (:y test-prepped)
    (:y (ml/predict test-prepped @model-c)))}))


(deftest t93_l597 (is ((fn [m] (< (:rmse m) 10.0)) v92_l593)))


(def v95_l606 (pocket/cleanup!))


(def v97_l624 (pocket/cleanup!))


(def v98_l626 (def depth-values [2 4 6 8 12]))


(def
 v99_l628
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
      #:metamorph{:id :clip-outlier}
      (clip-outlier-step)
      (mm/lift prepare-features :poly+trig)
      #:metamorph{:id :model}
      (pocket-model spec)))))))


(def
 v101_l647
 (def kfold-splits (tc/split->seq ds-500 :kfold {:k 3, :seed 42})))


(def
 v103_l652
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
 v104_l661
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


(def v105_l667 first-run-ms)


(def
 v107_l671
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


(def v108_l677 second-run-ms)


(deftest t109_l679 (is ((fn [ms] (< ms first-run-ms)) v108_l677)))


(def v111_l684 (pocket/cache-stats))


(deftest
 t112_l686
 (is ((fn [stats] (= 15 (:total-entries stats))) v111_l684)))


(def v114_l691 (def depth-results (run-depth-search)))


(def
 v115_l693
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


(def v116_l701 depth-summary)


(deftest
 t117_l703
 (is ((fn [rows] (= (count rows) (count depth-values))) v116_l701)))


(def v119_l714 (pocket/cleanup!))


(def
 v120_l716
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
       #:metamorph{:id :clip-outlier}
       (clip-outlier-step)
       (mm/lift prepare-features fs)
       #:metamorph{:id :model}
       (pocket-model spec))))
    [(mm/pipeline
      #:metamorph{:id :clip-outlier}
      (clip-outlier-step)
      (mm/lift prepare-features :poly+trig)
      #:metamorph{:id :model}
      (pocket-model linear-sgd-spec))]))))


(def
 v121_l738
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
 v123_l749
 (let
  [best
   (first
    (first
     (sort-by
      (fn*
       [p1__101869#]
       (get-in (first p1__101869#) [:test-transform :metric]))
      search-results)))]
  {:best-rmse (get-in best [:test-transform :metric]),
   :best-fit-ms (:timing-fit best)}))


(deftest t124_l755 (is ((fn [m] (< (:best-rmse m) 10.0)) v123_l749)))


(def v126_l762 (pocket/cache-stats))


(def
 v128_l778
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
 v129_l791
 (def
  scaling-results
  (vec
   (for
    [n [500 5000 10000]]
    (let
     [data
      (make-regression-data
       {:f nonlinear-fn,
        :n n,
        :noise-sd 0.5,
        :seed 42,
        :outlier-fraction 0.1,
        :outlier-scale 15})
      uncached-pipe
      (mm/pipeline
       #:metamorph{:id :clip-outlier}
       (clip-outlier-step)
       (mm/lift prepare-features :poly+trig)
       #:metamorph{:id :model}
       (ml/model cart-spec))
      cached-pipe
      (mm/pipeline
       #:metamorph{:id :clip-outlier}
       (clip-outlier-step)
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


(def v130_l817 (tc/dataset scaling-results))


(deftest
 t131_l819
 (is
  ((fn
    [ds]
    (let
     [row-10k (last (tc/rows ds :as-maps))]
     (< (:pocket-second-ms row-10k) (:uncached-ms row-10k))))
   v130_l817)))


(def
 v133_l847
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


(def v135_l901 (pocket/cleanup!))
