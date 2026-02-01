(ns scicloj.pocket.impl.cache
  (:require [babashka.fs :as fs]
            [taoensso.nippy :as nippy]
            [clojure.core.cache :as cc]
            [clojure.core.cache.wrapped :as cw]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (org.apache.commons.codec.digest DigestUtils)
           (clojure.lang PersistentHashMap IDeref Var)))

(defprotocol PIdentifiable
  (->id [this]))

(defn read-cached [path]
  (cond
    ;; nippy
    (fs/exists? (str path "/_.nippy"))
    (nippy/thaw-from-file (str path "/_.nippy"))
    ;; nil
    (fs/exists? (str path "/nil"))
    (do
      #_(fs/delete (str path "/nil"))
      nil)))

(defn write-cached! [v path]
  (fs/create-dirs path)
  (cond
    ;; nil
    (nil? v)
    (spit (str path "/nil") "")
    ;; else
    :else
    (nippy/freeze-to-file (str path "/_.nippy") v)))

(defn sha [^String s]
  (DigestUtils/sha1Hex s))

(def pocket-edn
  "Cached pocket.edn from classpath. Read once on first access."
  (delay
    (when-let [r (io/resource "pocket.edn")]
      (-> r slurp edn/read-string))))

(def default-mem-cache-options
  {:policy :lru :threshold 256})

(def current-mem-cache-options
  "Tracks the options the mem-cache was last configured with."
  (atom default-mem-cache-options))

(def default-mem-cache-threshold 256)

(defonce mem-cache
  (cw/lru-cache-factory {} :threshold default-mem-cache-threshold))

(def ^java.util.concurrent.ConcurrentHashMap in-flight
  (java.util.concurrent.ConcurrentHashMap.))

(defn clear-mem-cache! []
  (swap! mem-cache empty))

(defn reset-mem-cache!
  "Reset the in-memory cache with the given options.
   
   `:policy` — one of `:lru`, `:fifo`, `:lu`, `:ttl`, `:lirs`, `:soft`, `:basic` (default `:lru`).
   `:threshold` — max entries for `:lru`, `:fifo`, `:lu` (default 256).
   `:ttl` — time-to-live in ms for `:ttl` policy (default 30000).
   `:s-history-limit` / `:q-history-limit` — for `:lirs` policy."
  [{:keys [policy threshold ttl s-history-limit q-history-limit]
    :or {policy :lru
         threshold default-mem-cache-threshold
         ttl 30000}
    :as opts}]
  (let [effective {:policy policy :threshold threshold :ttl ttl}]
    (reset! current-mem-cache-options effective)
    (reset! mem-cache
            (case policy
              :basic (cc/basic-cache-factory {})
              :fifo (cc/fifo-cache-factory {} :threshold threshold)
              :lru (cc/lru-cache-factory {} :threshold threshold)
              :lu (cc/lu-cache-factory {} :threshold threshold)
              :ttl (cc/ttl-cache-factory {} :ttl ttl)
              :lirs (cc/lirs-cache-factory {} :s-history-limit (or s-history-limit threshold)
                                           :q-history-limit (or q-history-limit (quot threshold 4)))
              :soft (cc/soft-cache-factory {})
              (throw (ex-info (str "Unknown cache policy: " policy)
                              {:policy policy}))))
    effective))

(defn ensure-mem-cache!
  "Reconfigure the mem-cache if `opts` differ from current configuration.
   If `opts` is nil, does nothing."
  [opts]
  (when (and opts (not= opts @current-mem-cache-options))
    (reset-mem-cache! opts)))

(defn ->path [base-dir id typ]
  (let [h (-> id
              hash-unordered-coll
              str)]
    (str base-dir
         "/.cache/"
         (-> h
             sha
             (subs 0 2))
         "/"
         (let [idstr (str id)]
           (if (-> idstr
                   count
                   (> 250))
             (sha h)
             idstr)))))

(defn maybe-deref [x]
  (if (instance? IDeref x)
    @x
    x))

(deftype Cached [base-dir f args]
  IDeref
  (deref [this]
    (let [id (->id this)
          path (->path base-dir id
                       (-> f meta :type))]
      (cw/lookup-or-miss
       mem-cache path
       (fn [_]
         (let [d (.computeIfAbsent
                  in-flight path
                  (reify java.util.function.Function
                    (apply [_ _]
                      (delay
                        (try
                          (if (fs/exists? path)
                            (read-cached path)
                            (let [resolved-args (mapv maybe-deref args)
                                  v (clojure.core/apply f resolved-args)]
                              (write-cached! v path)
                              v))
                          (finally
                            (.remove in-flight path)))))))]
           @d))))))

(extend-protocol PIdentifiable
  Cached
  (->id [v]
    (list
     (->id (.f v))
     (->id (->> v
                .args
                (mapv (fn [a]
                        (cond (nil? a) nil
                              (instance? PersistentHashMap a) (->> a
                                                                   (sort-by key)
                                                                   (mapcat (fn [[k v]]
                                                                             [k (->id v)]))
                                                                   (apply array-map))
                              :else (->id a))))))))

  clojure.lang.MapEntry
  (->id [v] (pr-str v))

  Var
  (->id [this]
    (-> this symbol name))

  Object
  (->id [this]
    this))

(defn cached
  "Create a cached computation"
  [base-dir func & args]
  (->Cached base-dir func args))

(defn caching-fn [base-dir f]
  (fn [& args]
    (apply cached base-dir f args)))

(defn invalidate!
  "Invalidate a specific cached computation by deleting its disk and memory entries.
   Returns a map with `:path` and `:existed`."
  [base-dir func args]
  (let [c (->Cached base-dir func args)
        id (->id c)
        path (->path base-dir id (-> func meta :type))
        existed? (boolean (fs/exists? path))]
    (when existed?
      (fs/delete-tree path))
    (swap! mem-cache dissoc path)
    (.remove in-flight path)
    {:path path :existed existed?}))

(defn invalidate-fn!
  "Invalidate all cached entries for a given function var.
   Scans the cache directory for entries whose path contains the function name.
   Returns a map with `:fn-name`, `:count`, and `:paths`."
  [base-dir func]
  (let [fn-name (->id func)
        prefix (str "(\"" fn-name "\"")
        cache-dir (str base-dir "/.cache")
        deleted-paths (atom [])]
    (when (fs/exists? cache-dir)
      (doseq [prefix-dir (fs/list-dir cache-dir)
              :when (fs/directory? prefix-dir)]
        (doseq [entry-dir (fs/list-dir prefix-dir)
                :let [entry-name (str (fs/file-name entry-dir))]
                :when (and (fs/directory? entry-dir)
                           (.startsWith entry-name prefix))]
          (let [path (str entry-dir)]
            (fs/delete-tree entry-dir)
            (swap! mem-cache dissoc path)
            (.remove in-flight path)
            (swap! deleted-paths conj path)))))
    {:fn-name fn-name
     :count (count @deleted-paths)
     :paths @deleted-paths}))

;; Fix: Add nil handling to PIdentifiable protocol
(extend-protocol PIdentifiable
  nil
  (->id [_] nil))
