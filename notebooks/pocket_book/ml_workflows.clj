;; # Example: Machine Learning Workflows

;; This chapter demonstrates Pocket in a realistic machine learning
;; scenario. If you're new to ML, don't worry — we'll explain the
;; concepts as we go. The focus is on how caching helps when you're
;; exploring many combinations of data, features, and models.
;;
;; **The problem**: We want to predict a numeric value (like house
;; prices or temperature) from input data. This is called *regression*.
;; We'll generate synthetic data, try different ways of preparing it,
;; and compare two learning algorithms.
;;
;; **Why caching matters**: Training models can be slow. When you're
;; experimenting — tweaking parameters, trying new features — you don't
;; want to recompute everything each time. Pocket caches each step
;; independently, so only the parts you changed get recomputed.
;;
;; **What we'll cover**:
;; - Part 1: Feature engineering — transforming inputs to help models learn
;; - Part 2: Noise sensitivity — how models behave with messy data
;; - Part 3: The caching payoff — what got cached and why it matters
;; - Part 4: DAG workflows — when preprocessing steps share dependencies
;; - Part 5: Hyperparameter sweeps — comparing many experiments at once
;;
;; **Note**: This notebook uses
;; [tablecloth](https://scicloj.github.io/tablecloth/) for data manipulation,
;; [metamorph.ml](https://github.com/scicloj/metamorph.ml) and
;; [tribuo](https://github.com/scicloj/scicloj.ml.tribuo) for ML, and
;; [Plotly.js](https://plotly.com/javascript/) for visualization.
;; These are not Pocket dependencies — they illustrate a realistic ML
;; workflow. All output is shown inline; to reproduce it, add
;; [noj](https://scicloj.github.io/noj/) to your project dependencies.
;;
;; **Why synthetic data?** Working with synthetic data is a standard
;; practice in machine learning. Because we define the true relationship
;; ($y = \sin(x) \cdot x$), we can measure exactly how well each model
;; recovers it — something impossible with real-world data where the
;; ground truth is unknown. Synthetic experiments let us isolate one
;; variable at a time: does feature engineering help? How does noise
;; affect each algorithm? These controlled comparisons build intuition
;; that transfers to real problems. In our case, we'll see that a linear
;; model is helpless against a nonlinear target *unless* we give it the
;; right features, while a decision tree handles the shape on its own
;; but pays a different price when noise increases.

;; ## Setup

(ns pocket-book.ml-workflows
  (:require
   ;; Logging setup for this chapter (see Logging chapter):
   [pocket-book.logging]
   ;; Pocket API:
   [scicloj.pocket :as pocket]
   ;; Annotating kinds of visualizations:
   [scicloj.kindly.v4.kind :as kind]
   ;; Data processing:
   [tablecloth.api :as tc]
   [tablecloth.column.api :as tcc]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.modelling :as ds-mod]
   ;; Machine learning:
   [scicloj.metamorph.ml :as ml]
   [scicloj.metamorph.ml.loss :as loss]
   [scicloj.ml.tribuo]))

(def cache-dir "/tmp/pocket-regression")

(pocket/set-base-cache-dir! cache-dir)

(pocket/cleanup!)

;; ## Pipeline functions

;; These are the steps of our ML pipeline — plain Clojure functions
;; that know nothing about caching. Pocket will wrap them later.

;; **Data generation**: `make-regression-data` creates a synthetic
;; dataset from a ground-truth function. We control the sample size,
;; noise level, and random seed — all of which become part of the
;; cache key, so changing any parameter triggers recomputation.

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

;; **Feature engineering**: `prepare-features` transforms raw data
;; by adding derived columns. The choice of feature set is a key
;; hyperparameter — a linear model with only `:raw` features can't
;; learn nonlinear patterns, but `:trig` or `:poly+trig` features
;; give it the building blocks it needs.

(defn prepare-features
  "Add derived columns to a dataset according to `feature-set`.
  Supported feature sets:
  - `:raw`       — no extra columns
  - `:quadratic` — add x²
  - `:trig`      — add sin(x) and cos(x)
  - `:poly+trig` — add x², sin(x), and cos(x)"
  [ds feature-set]
  (let [x (:x ds)]
    (-> (case feature-set
          :raw ds
          :quadratic (-> ds
                         (tc/add-column :x2 (tcc/sq x)))
          :trig (-> ds
                    (tc/add-column :sin-x (tcc/sin x))
                    (tc/add-column :cos-x (tcc/cos x)))
          :poly+trig (-> ds
                         (tc/add-column :x2 (tcc/sq x))
                         (tc/add-column :sin-x (tcc/sin x))
                         (tc/add-column :cos-x (tcc/cos x))))
        (ds-mod/set-inference-target :y))))

;; **Training and evaluation**: `train-model` fits a model to
;; prepared data, and `predict-and-rmse` measures how well it
;; generalizes to unseen test data. These are thin wrappers
;; around metamorph.ml — the caching value comes from avoiding
;; redundant retraining when only downstream parameters change.

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

;; We need a function to predict. In real problems you don't know
;; the true relationship — that's what you're trying to learn. Here
;; we define it explicitly so we can measure how well our models do.
;;
;; Our target is $y = \sin(x) \cdot x$ — a wavy curve that grows
;; with $x$. A straight line can't fit this shape, so a simple
;; linear model will struggle unless we help it with better features.

(defn nonlinear-fn
  "y = sin(x) · x"
  [x]
  (* (Math/sin x) x))

;; ## Model specifications

;; We'll compare two fundamentally different algorithms:
;;
;; **Linear model** (gradient descent): Finds the best straight-line
;; (or hyperplane) relationship between inputs and output. Simple and
;; fast, but can only learn linear patterns. Needs good features.
;;
;; **Decision tree** (CART): Learns by splitting data into regions
;; based on thresholds ("if x > 5, go left"). Can capture complex
;; patterns automatically, but may overfit noisy data.
;;
;; These algorithms respond differently to feature engineering —
;; that contrast is the heart of Part 1.

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

;; ## Part 1 — Feature engineering matters (for some models)

;; *Feature engineering* means transforming raw inputs into forms
;; that help models learn. For example, if the true relationship
;; involves $x^2$, adding a squared column gives the model that
;; pattern directly instead of forcing it to discover it.
;;
;; We'll test four feature sets:
;; - `:raw` — just the original $x$ value
;; - `:quadratic` — add $x^2$
;; - `:trig` — add $\sin(x)$ and $\cos(x)$
;; - `:poly+trig` — add all three
;;
;; Crossed with two model types, that's eight combinations. Every
;; step is cached, so re-running is instant.

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

;; **What the results show**:
;;
;; The linear model (SGD) has high error with raw features — it's
;; trying to draw a straight line through a wavy curve. But give it
;; $\sin(x)$ and $\cos(x)$ as features, and it can combine them to
;; approximate the true shape. Feature engineering saved the day.
;;
;; The decision tree (CART) doesn't care. It discovers the wavy
;; pattern by splitting the data into regions. Extra features
;; don't help because the tree already found the structure.
;;
;; **Takeaway**: Some models need feature engineering; others don't.
;; Caching lets you explore both without waiting.

;; ### Predictions plot

;; Best linear model (poly+trig) vs best tree (raw) vs actual values.

(let [test-ds (prepared [:raw :test])
      sgd-pred (:y (ml/predict (prepared [:poly+trig :test])
                               (models [:poly+trig :sgd])))
      cart-pred (:y (ml/predict test-ds
                                (models [:raw :cart])))
      xs (vec (:x test-ds))
      actuals (vec (:y test-ds))
      sgd-vals (vec sgd-pred)
      cart-vals (vec cart-pred)]
  (kind/plotly
   {:data [{:x xs :y actuals :mode "markers" :name "actual"
            :marker {:opacity 0.3 :color "gray"}}
           {:x xs :y sgd-vals :mode "markers" :name "Linear SGD (poly+trig)"
            :marker {:opacity 0.5 :color "steelblue"}}
           {:x xs :y cart-vals :mode "markers" :name "CART (raw)"
            :marker {:opacity 0.5 :color "tomato"}}]
    :layout {:xaxis {:title "x"} :yaxis {:title "y"}}}))

;; ---

;; ## Part 2 — How models handle noisy data

;; Real data is messy. Measurements have errors, inputs are approximate.
;; *Noise* is the random variation that obscures the true pattern.
;;
;; How do our models behave as noise increases? We'll test five
;; levels, from nearly clean (0.1) to very noisy (5.0).
;;
;; Notice: the noise=0.5 dataset reuses the cache from Part 1 —
;; Pocket recognizes the same function and arguments.

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

;; **What the results show**:
;;
;; At low noise, the tree wins — it captures fine details the linear
;; model smooths over. But as noise increases, the tree starts
;; memorizing random wiggles (*overfitting*), and its error explodes.
;;
;; The linear model degrades more gracefully. Its rigid structure
;; (a weighted sum of features) acts as a built-in regularizer —
;; it can't chase noise even if it wanted to.
;;
;; **Takeaway**: Flexible models (trees) excel with clean data but
;; suffer with noise. Simple models (linear) are more robust.

;; ### RMSE vs. noise

(let [noise-sds (vec (map :noise-sd noise-results))
      cart-rmses (vec (map :cart-rmse noise-results))
      sgd-rmses (vec (map :sgd-rmse noise-results))]
  (kind/plotly
   {:data [{:x noise-sds :y cart-rmses :mode "lines+markers" :name "CART"}
           {:x noise-sds :y sgd-rmses :mode "lines+markers" :name "Linear SGD"}]
    :layout {:xaxis {:title "noise-sd"} :yaxis {:title "rmse"}}}))

;; ---

;; ## Part 3 — What got cached?

;; We've run many combinations of data, features, and models.
;; Each `pocket/cached` call created an independent cache entry.
;; Let's see what we accumulated:

(:total-entries (pocket/cache-stats))

(kind/test-last
 [(fn [n] (> n 30))])

(pocket/cache-entries)

;; With this small synthetic data, each step runs in milliseconds.
;; But the *structure* is what matters. In real workflows — large
;; datasets, deep neural networks, hyperparameter searches — the
;; same cache graph saves hours or days.
;;
;; Here's what happens when you change something:
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

;; ## Part 4 — Sharing computations across branches

;; Real preprocessing often requires computing something from the
;; training data and applying it to both train and test sets.
;; For example, *normalization*: you compute the mean and standard
;; deviation from training data, then use those same values to
;; scale both sets. (Using test statistics would leak information.)
;;
;; This creates a *diamond dependency* — one computation feeds
;; into multiple downstream steps:
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
;; Pocket handles this naturally. The `stats-c` node is computed
;; once and feeds both preprocessing steps. When you change the
;; training data, stats recompute, and both branches update.

;; ### Pipeline functions
;;
;; These are plain functions. Each does one thing: compute stats,
;; normalize data, train, or evaluate. Pocket will wire them together.

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
                   (tcc// (tcc/- (:x ds) x-mean) x-std))))

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

;; ### Build the DAG with mixed storage policies
;;
;; Not every step needs disk persistence. We use `caching-fn` with
;; per-function storage policies:
;;
;; - **`:mem`** for cheap shared computations (stats, normalization) —
;;   no disk I/O, but in-memory dedup ensures each runs only once
;; - **`:mem+disk`** (default) for expensive steps (model training) —
;;   persists across JVM sessions
;; - **`:none`** for trivial steps (evaluation) — just tracks identity
;;   in the DAG without any shared caching

(def c-compute-stats
  (pocket/caching-fn #'compute-stats {:storage :mem}))

(def c-normalize
  (pocket/caching-fn #'normalize-with-stats {:storage :mem}))

(def c-train
  (pocket/caching-fn #'train-normalized-model))

(def c-evaluate
  (pocket/caching-fn #'evaluate-model {:storage :none}))

;; Generate fresh data for this demo:

(def dag-data
  @(pocket/cached #'make-regression-data #'nonlinear-fn 200 0.3 99))

(def dag-split
  (first (tc/split->seq dag-data :holdout {:seed 99})))

;; Now wire the pipeline. The `stats-c` node is computed once
;; (in memory) and feeds both preprocessing steps — a diamond
;; dependency handled naturally.

(def stats-c
  (c-compute-stats (:train dag-split)))

(def train-norm-c
  (c-normalize (:train dag-split) stats-c))

(def test-norm-c
  (c-normalize (:test dag-split) stats-c))

(def model-c
  (c-train train-norm-c cart-spec))

(def metrics-c
  (c-evaluate test-norm-c model-c))

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

(pocket/origin-story-mermaid metrics-c)

;; ### Execute the pipeline

(deref metrics-c)

(kind/test-last
 [(fn [m] (and (map? m) (contains? m :rmse)))])

;; ---

;; ## Part 5 — Comparing many experiments at once

;; *Hyperparameters* are settings you choose before training: tree
;; depth, learning rate, which features to use. Finding good values
;; usually means trying many combinations — a *hyperparameter sweep*.
;;
;; Pocket's `compare-experiments` helps here. You pass a collection
;; of cached experiments, and it extracts the parameters that vary
;; across them (ignoring ones that are constant).

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

;; Each row shows the varying parameters plus the result. Parameters
;; that were constant (like seed=42) are excluded automatically —
;; you see only what differs.

;; ### Results visualization

(let [rows (map (fn [exp]
                  (merge (select-keys exp [:noise-sd :feature-set :max-depth])
                         (:result exp)))
                comparison)
      ;; Group by both feature-set and noise-sd for legend entries
      grouped (group-by (juxt :feature-set :noise-sd) rows)
      feature-colors {:raw "steelblue" :poly "tomato" :poly+trig "green"}]
  (kind/plotly
   {:data (vec (for [[[feature-set noise-sd] pts] (sort-by first grouped)
                     :let [max-depths (mapv :max-depth pts)
                           rmses (mapv :rmse pts)]]
                 {:x max-depths
                  :y rmses
                  :mode "markers"
                  :name (str (name feature-set) " (noise=" noise-sd ")")
                  :legendgroup (name feature-set)
                  :marker {:size (+ 8 (* 15 noise-sd))
                           :color (feature-colors feature-set)}}))

    :layout {:xaxis {:title "max-depth"} :yaxis {:title "rmse"}}}))

;; ## What we learned
;;
;; This experiment revealed a clear story about the interplay between
;; models, features, and noise:
;;
;; - **Feature engineering is decisive for linear models.** With raw
;;   features, the linear model couldn't capture the nonlinear target
;;   at all. Adding trigonometric features (sin, cos) — which match
;;   the structure of the true function — dramatically improved it.
;;   The model didn't get smarter; we gave it the right vocabulary.
;;
;; - **Decision trees are self-sufficient but fragile.** The CART model
;;   achieved low error regardless of feature set, because it can
;;   learn nonlinear splits on its own. But as noise increased, it
;;   began fitting the noise rather than the signal — a classic
;;   overfitting pattern.
;;
;; - **The crossover point matters.** At low noise, the tree wins. At
;;   high noise, the well-featured linear model degrades more
;;   gracefully. Knowing where this crossover happens is exactly the
;;   kind of insight you get from systematic experimentation.
;;
;; - **Caching structures the workflow.** In this small example, each
;;   step runs in milliseconds — caching isn't needed for speed. But
;;   the pattern scales: with real datasets and expensive training,
;;   the same pipeline structure ensures that only changed steps
;;   recompute. Meanwhile, `compare-experiments` extracted the varying
;;   parameters automatically, turning cached results into a
;;   comparison table — useful at any scale.

;; ## Cleanup

(pocket/cleanup!)
