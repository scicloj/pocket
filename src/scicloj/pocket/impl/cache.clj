(ns scicloj.pocket.impl.cache
  (:require [babashka.fs :as fs]
            [taoensso.nippy :as nippy]
            [clojure.core.cache :as cc]
            [clojure.core.cache.wrapped :as cw])
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
         ttl 30000}}]
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
                            {:policy policy})))))

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
                            (let [v (clojure.core/apply f args)]
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

(defn maybe-deref [x]
  (if (instance? IDeref x)
    @x
    x))

(defn cached-fn [base-dir f]
  (fn [& args]
    (apply cached base-dir f args)))

;; Fix: Add nil handling to PIdentifiable protocol
(extend-protocol PIdentifiable
  nil
  (->id [_] nil))
