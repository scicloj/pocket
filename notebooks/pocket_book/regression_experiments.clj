;; # Regression Experiments

;; Training a machine learning model is often expensive. When you are
;; exploring combinations of feature engineering and model types,
;; re-evaluating a notebook should not recompute everything from scratch.
;;
;; Pocket caches each step independently. Change one feature set and
;; only the affected models retrain. Change the noise level and only
;; that data regenerates. With larger data or heavier models, the same
;; cache graph that we build here saves hours instead of seconds.
;;
;; This chapter walks through a regression problem with synthetic data,
;; two algorithms, four feature-engineering strategies, and five noise
;; levels — a combinatorial exploration where every step is cached.
;;
;; **Prerequisites**: This notebook uses libraries from the `:dev` alias
;; in `deps.edn` — [tablecloth](https://scicloj.github.io/tablecloth/),
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml),
;; [tribuo](https://github.com/scicloj/scicloj.ml.tribuo), and
;; [tableplot](https://scicloj.github.io/tableplot/). Start your REPL
;; with `clojure -M:dev` to have them available.

;; ## Setup

(ns pocket-book.regression-experiments
  (:require [pocket-book.logging]
            [scicloj.pocket :as pocket]
            [scicloj.kindly.v4.kind :as kind]
            [tablecloth.api :as tc]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.modelling :as ds-mod]
            [scicloj.metamorph.ml :as ml]
            [scicloj.metamorph.ml.loss :as loss]
            [scicloj.ml.tribuo]
            [scicloj.tableplot.v1.plotly :as plotly]))

(def cache-dir "/tmp/pocket-regression")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## Helper functions

;; These are the building blocks. Each is a plain function that
;; knows nothing about caching.

(defn make-regression-data
  "Generate a synthetic regression dataset.
  `f` is a function from x to y (the ground truth).
  Returns a dataset with columns `:x` and `:y`."
  [f n noise-sd seed]
  (let [rng (java.util.Random. (long seed))
        xs (vec (repeatedly n #(* 10.0 (.nextDouble rng))))
        ys (mapv (fn [x] (+ (double (f x))
                            (* (double noise-sd) (.nextGaussian rng))))
                 xs)]
    (-> (tc/dataset {:x xs :y ys})
        (ds-mod/set-inference-target :y))))

(defn prepare-features
  "Add derived columns to a dataset according to `feature-set`.
  Supported feature sets:
  - `:raw`       — no extra columns
  - `:quadratic` — add x²
  - `:trig`      — add sin(x) and cos(x)
  - `:poly+trig` — add x², sin(x), and cos(x)"
  [ds feature-set]
  (let [xv (vec (:x ds))]
    (-> (case feature-set
          :raw ds
          :quadratic (-> ds
                         (tc/add-column :x2 (mapv #(* % %) xv)))
          :trig (-> ds
                    (tc/add-column :sin-x (mapv #(Math/sin %) xv))
                    (tc/add-column :cos-x (mapv #(Math/cos %) xv)))
          :poly+trig (-> ds
                         (tc/add-column :x2 (mapv #(* % %) xv))
                         (tc/add-column :sin-x (mapv #(Math/sin %) xv))
                         (tc/add-column :cos-x (mapv #(Math/cos %) xv))))
        (ds-mod/set-inference-target :y))))

(defn train-model
  "Train a model on a dataset."
  [train-ds model-spec]
  (ml/train train-ds model-spec))

(defn predict-and-rmse
  "Predict on test data and return RMSE."
  [test-ds model]
  (let [pred (ml/predict test-ds model)]
    (loss/rmse (:y test-ds) (:y pred))))

;; ## Ground truth

;; Our target function is $y = \sin(x) \cdot x$. A linear model
;; cannot capture this shape without help from feature engineering.

(defn nonlinear-fn
  "y = sin(x) · x"
  [x]
  (* (Math/sin x) x))

;; ## Model specifications

;; A linear model (gradient descent) and a decision tree.
;; They have fundamentally different relationships with feature
;; engineering — that contrast is the heart of Part 1.

(def linear-sgd-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "squared"
                        :type "org.tribuo.regression.sgd.objectives.SquaredLoss"}
                       {:name "trainer"
                        :type "org.tribuo.regression.sgd.linear.LinearSGDTrainer"
                        :properties {:objective "squared"
                                     :epochs "50"
                                     :loggingInterval "10000"}}]
   :tribuo-trainer-name "trainer"})

(def cart-spec
  {:model-type :scicloj.ml.tribuo/regression
   :tribuo-components [{:name "cart"
                        :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                        :properties {:maxDepth "8"}}]
   :tribuo-trainer-name "cart"})

;; ---

;; ## Part 1 — Feature engineering × model type

;; The ground truth is $y = \sin(x) \cdot x$. We generate 500 points
;; with moderate noise and explore four feature sets crossed with
;; two model types — eight models in total. Every step is cached.

;; ### Generate data

(def data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 500 0.5 42))

(tc/head data)

;; ### Split into train and test

(def split
  (first (tc/split->seq data :holdout {:seed 42})))

;; ### Feature sets

(def feature-sets [:raw :quadratic :trig :poly+trig])

;; ### Prepare features (cached)

;; Each feature set applied to each split half is a separate
;; cached computation — eight in total.

(def prepared
  (into {}
        (for [fs feature-sets
              role [:train :test]]
          [[fs role]
           @(pocket/cached #'prepare-features (role split) fs)])))

;; ### Train models (cached)

;; Two models per feature set — eight cached training runs.

(def models
  (into {}
        (for [fs feature-sets
              [model-name spec] [[:sgd linear-sgd-spec]
                                 [:cart cart-spec]]]
          [[fs model-name]
           @(pocket/cached #'train-model
                           (prepared [fs :train])
                           spec)])))

;; ### Results

(def feature-results
  (vec (for [fs feature-sets
             [model-name _] [[:sgd linear-sgd-spec]
                             [:cart cart-spec]]]
         {:feature-set fs
          :model (name model-name)
          :rmse (predict-and-rmse (prepared [fs :test])
                                  (models [fs model-name]))})))

feature-results

(kind/test-last
 [(fn [rows]
    (let [m (into {} (map (juxt (juxt :feature-set :model) :rmse) rows))]
      (and (> (m [:raw "sgd"]) 3.0)
           (< (m [:poly+trig "sgd"]) 2.0)
           (< (Math/abs (- (m [:raw "cart"]) (m [:trig "cart"]))) 0.5))))])

;; A linear model's world is defined by its features. With only raw
;; $x$, it fits a straight line through a sine wave — hopeless. Give
;; it $\sin(x)$ and $\cos(x)$ and it can approximate the curve.
;;
;; A decision tree discovers nonlinearity through splits. Extra
;; features do not help — the tree already found the shape.

;; ### Predictions plot

;; Best linear model (poly+trig) vs best tree (raw) vs actual values.

(let [test-ds (prepared [:raw :test])
      sgd-pred (:y (ml/predict (prepared [:poly+trig :test])
                               (models [:poly+trig :sgd])))
      cart-pred (:y (ml/predict test-ds
                                (models [:raw :cart])))]
  (-> (tc/dataset {:x (:x test-ds)
                   :actual (:y test-ds)
                   :Linear-SGD sgd-pred
                   :CART cart-pred})
      (plotly/layer-point {:=x :x :=y :actual :=name "actual"
                           :=mark-opacity 0.3 :=mark-color "gray"})
      (plotly/layer-point {:=x :x :=y :Linear-SGD :=name "Linear SGD (poly+trig)"
                           :=mark-opacity 0.5 :=mark-color "steelblue"})
      (plotly/layer-point {:=x :x :=y :CART :=name "CART (raw)"
                           :=mark-opacity 0.5 :=mark-color "tomato"})))

;; ---

;; ## Part 2 — Noise sensitivity

;; How do these models behave as noise increases? We generate data
;; at five noise levels. The noise=0.5 dataset reuses the cache
;; from Part 1 — Pocket recognizes the same arguments.

(def noise-levels [0.1 0.5 1.0 2.0 5.0])

(def noise-results
  (vec
   (for [noise-sd noise-levels]
     (let [ds @(pocket/cached #'make-regression-data
                              #'nonlinear-fn 500 noise-sd 42)
           sp (first (tc/split->seq ds :holdout {:seed 42}))
           cart-train @(pocket/cached #'prepare-features (:train sp) :raw)
           cart-test @(pocket/cached #'prepare-features (:test sp) :raw)
           sgd-train @(pocket/cached #'prepare-features (:train sp) :poly+trig)
           sgd-test @(pocket/cached #'prepare-features (:test sp) :poly+trig)
           cart-model @(pocket/cached #'train-model cart-train cart-spec)
           sgd-model @(pocket/cached #'train-model sgd-train linear-sgd-spec)]
       {:noise-sd noise-sd
        :cart-rmse (predict-and-rmse cart-test cart-model)
        :sgd-rmse (predict-and-rmse sgd-test sgd-model)}))))

noise-results

(kind/test-last
 [(fn [rows]
    (let [low (first (filter #(= 0.1 (:noise-sd %)) rows))
          high (first (filter #(= 5.0 (:noise-sd %)) rows))]
      (and (< (:cart-rmse low) (:sgd-rmse low))
           (> (:cart-rmse high) (:sgd-rmse high)))))])

;; At low noise the tree captures fine structure that the linear
;; model misses. As noise increases the tree overfits to randomness,
;; while the linear model — constrained by its parametric form —
;; degrades more gracefully.

;; ### RMSE vs. noise

(let [rows (mapcat (fn [{:keys [noise-sd cart-rmse sgd-rmse]}]
                     [{:noise-sd noise-sd :model "CART" :rmse cart-rmse}
                      {:noise-sd noise-sd :model "Linear SGD" :rmse sgd-rmse}])
                   noise-results)]
  (-> (tc/dataset rows)
      (plotly/layer-line {:=x :noise-sd :=y :rmse :=color :model})
      (plotly/layer-point {:=x :noise-sd :=y :rmse :=color :model})))

;; ---

;; ## Part 3 — The caching payoff

;; This notebook generated and trained many combinations of data,
;; features, and models. Each call to `pocket/cached` is an
;; independent cache entry.

(:total-entries (pocket/cache-stats))

(kind/test-last
 [(fn [n] (> n 30))])

(pocket/cache-entries)

;; With small synthetic data every step runs quickly. The same
;; structure applies to real workflows where data generation
;; takes minutes and model training takes hours. The cache
;; graph is identical — only the savings grow.
;;
;; | Change                        | What recomputes          |
;; |-------------------------------|--------------------------|
;; | Edit a feature set            | That feature prep + its models |
;; | Change a model hyperparameter | Only that model          |
;; | Change the noise level        | That data + its features + its models |
;; | Re-run the whole notebook     | Nothing — all cached     |

;; ## Cleanup

(pocket/cleanup!)

;; ---

;; ## Part 4 — DAG workflow with shared statistics

;; In real preprocessing pipelines, statistics computed from the
;; training set (e.g., mean, std, outlier thresholds) must be applied
;; to both training and test sets. This creates a diamond dependency:
;;
;; ```
;;     train/test split
;;          │
;;     ┌────┴────┐
;;     ▼         ▼
;;   train     test
;;     │
;;     ▼
;;  compute-stats
;;     │
;;     ├─────────────────┐
;;     ▼                 ▼
;; preprocess(train)  preprocess(test)
;;     │                 │
;;     ▼                 │
;; train-model          │
;;     │                 │
;;     ├─────────────────┘
;;     ▼
;;   evaluate
;; ```
;;
;; Pocket's DAG support handles this naturally — the shared `stats`
;; node appears once in the computation graph and is computed once.

;; ### Pipeline functions

(defn compute-stats
  "Compute normalization statistics from training data.
   Returns mean and std for each numeric column."
  [train-ds]
  (println "  Computing stats from training data...")
  (let [x-vals (vec (:x train-ds))]
    {:x-mean (/ (reduce + x-vals) (count x-vals))
     :x-std (Math/sqrt (/ (reduce + (map #(* (- % (/ (reduce + x-vals) (count x-vals)))
                                             (- % (/ (reduce + x-vals) (count x-vals))))
                                         x-vals))
                          (count x-vals)))}))

(defn normalize-with-stats
  "Normalize a dataset using pre-computed statistics."
  [ds stats]
  (println "  Normalizing with stats:" stats)
  (let [{:keys [x-mean x-std]} stats]
    (tc/add-column ds :x-norm
                   (mapv #(/ (- % x-mean) x-std) (:x ds)))))

(defn train-normalized-model
  "Train a model on normalized data."
  [train-ds model-spec]
  (println "  Training model on normalized data...")
  (ml/train train-ds model-spec))

(defn evaluate-model
  "Evaluate a model on test data."
  [test-ds model]
  (println "  Evaluating model...")
  (let [pred (ml/predict test-ds model)]
    {:rmse (loss/rmse (:y test-ds) (:y pred))}))

;; ### Build the DAG

;; Generate fresh data for this demo:

(def dag-data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 200 0.3 99))

(def dag-split
  (first (tc/split->seq dag-data :holdout {:seed 99})))

;; The key insight: `stats-c` is a cached computation derived from
;; training data only. Both preprocessing steps depend on it.

(def stats-c
  (pocket/cached #'compute-stats (:train dag-split)))

(def train-norm-c
  (pocket/cached #'normalize-with-stats (:train dag-split) stats-c))

(def test-norm-c
  (pocket/cached #'normalize-with-stats (:test dag-split) stats-c))

(def model-c
  (pocket/cached #'train-normalized-model train-norm-c cart-spec))

(def metrics-c
  (pocket/cached #'evaluate-model test-norm-c model-c))

;; ### Visualize the DAG

;; Pocket provides three functions for DAG introspection, each suited
;; to different use cases.

;; **`origin-story`** returns a nested tree structure. Each cached node
;; has `:fn`, `:args`, and `:id`. The `:id` is unique; when the same
;; `Cached` instance appears multiple times (diamond pattern), subsequent
;; occurrences become `{:ref <id>}` pointers. This avoids infinite
;; recursion and makes the diamond explicit:

(pocket/origin-story metrics-c)

;; Notice how `stats-c` appears as a `:ref` in one branch — it's the
;; same computation feeding both train and test normalization.

;; **`origin-story-graph`** normalizes the tree into a flat
;; `{:nodes ... :edges ...}` structure suitable for graph algorithms:

(pocket/origin-story-graph metrics-c)

;; **`origin-story-mermaid`** renders the DAG as a Mermaid flowchart, with
;; arrows showing data flow direction (from inputs toward the final result).
;; The diamond dependency is clearly visible — `stats-c` feeds both
;; preprocessing steps:

(kind/mermaid (pocket/origin-story-mermaid metrics-c))

;; ### Execute the pipeline

(deref metrics-c)

(kind/test-last
 [(fn [m] (and (map? m) (contains? m :rmse)))])

;; ---

;; ## Part 5 — Hyperparameter sweep with compare-experiments

;; When running multiple experiments with different hyperparameters,
;; `compare-experiments` extracts the varying parameters automatically.

(defn run-pipeline
  "Run a complete pipeline with given hyperparameters."
  [{:keys [noise-sd feature-set max-depth]}]
  (let [ds (make-regression-data nonlinear-fn 200 noise-sd 42)
        sp (first (tc/split->seq ds :holdout {:seed 42}))
        train-prep (prepare-features (:train sp) feature-set)
        test-prep (prepare-features (:test sp) feature-set)
        spec {:model-type :scicloj.ml.tribuo/regression
              :tribuo-components [{:name "cart"
                                   :type "org.tribuo.regression.rtree.CARTRegressionTrainer"
                                   :properties {:maxDepth (str max-depth)}}]
              :tribuo-trainer-name "cart"}
        model (ml/train train-prep spec)
        pred (ml/predict test-prep model)]
    {:rmse (loss/rmse (:y test-prep) (:y pred))}))

;; Run experiments across a grid of hyperparameters:

(def experiments
  (for [noise-sd [0.3 0.5]
        feature-set [:raw :poly+trig]
        max-depth [4 8]]
    (pocket/cached #'run-pipeline
                   {:noise-sd noise-sd
                    :feature-set feature-set
                    :max-depth max-depth})))

;; Compare all experiments — only varying parameters are shown:

(def comparison
  (pocket/compare-experiments experiments))

(tc/dataset comparison)

(kind/test-last
 [(fn [ds]
    (and (= 8 (tc/row-count ds))
         (some #{:noise-sd} (tc/column-names ds))
         (some #{:feature-set} (tc/column-names ds))
         (some #{:max-depth} (tc/column-names ds))))])

;; The `:result` column contains the metrics from each experiment.
;; Parameters that were the same across all experiments (like seed=42)
;; are automatically excluded from the comparison table.

;; ### Results visualization

(let [rows (map (fn [exp]
                  (merge (select-keys exp [:noise-sd :feature-set :max-depth])
                         (:result exp)))
                comparison)]
  (-> (tc/dataset rows)
      (plotly/layer-point {:=x :max-depth :=y :rmse
                           :=color :feature-set
                           :=size :noise-sd})))

;; ## Cleanup

(pocket/cleanup!)
