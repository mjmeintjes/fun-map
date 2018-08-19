(ns robertluo.fun-map.core
  "implementation of fun-maps.

  a fun-map delegates its storage to underlying map m,
  m stores k,v pair with value wrapped inside a wrapper,
  when requested an entry from a fun-map, instead of
  returning map entry like an ordinary map, it returns
  a special version of entry, evaluate it's value by
  invoking the wrapper."
  (:import [clojure.lang
            IMapEntry
            APersistentMap]))

(defprotocol ValueWrapper
  (unwrap [this m]
    "unwrap the real value from a wrapper"))

(defn deep-unwrap [o m]
  (if (satisfies? ValueWrapper o)
    (recur (unwrap o m) m)
    o))

(defn wrapped-entry [m ^IMapEntry entry]
  (proxy [clojure.lang.MapEntry] [(.key entry) (.val entry)]
    (val []
      (deep-unwrap (proxy-super val) m))))

(definterface IFunMap)

(defn delegate-map [^APersistentMap m]
  (proxy [APersistentMap clojure.lang.IObj IFunMap] []
    (meta []
      (.meta m))

    (withMeta [mdata]
      (delegate-map (.withMeta m mdata)))

    (containsKey [k]
      (.containsKey m k))

    (valAt
      ([k]
       (some-> (.entryAt this k) (.val)))
      ([k not-found]
       (if (.containsKey this k)
         (.valAt this k)
         not-found)))

    (entryAt [k]
      (when (.containsKey m k)
        (wrapped-entry this (.entryAt m k))))

    (assoc [k v]
      (delegate-map (.assoc m k v)))

    (assocEx [k v]
      (delegate-map (.assocEx m k v)))

    (empty []
      (delegate-map (.empty m)))

    (without [k]
      (delegate-map (.dissoc m k)))

    (count []
      (.count m))

    (iterator []
      (let [ite (.iterator m)]
        (reify java.util.Iterator
          (hasNext [_]
            (.hasNext ite))
          (next [_]
            (wrapped-entry this (.next ite))))))

    (seq []
      (clojure.lang.IteratorSeq/create (.iterator this)))))

;;;;;;;;;;;; Function wrapper

(extend-protocol ValueWrapper
  clojure.lang.IDeref
  (unwrap [d _]
    (deref d)))

(deftype FunctionWrapper [val f]
  ValueWrapper
  (unwrap [_ m]
    (when (= ::unrealized @val)
      (reset! val (f m)))
    val))

(defn fn-wrapper [f]
  (->FunctionWrapper (atom ::unrealized) f))

(deftype CloseableValue [value close-fn]
  clojure.lang.IDeref
  (deref [_]
    value)
  java.io.Closeable
  (close [_]
    (close-fn)))
