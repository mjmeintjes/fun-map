(ns ^:no-doc robertluo.pull.impl
  "Implementation of pull")

(defprotocol Findable
  :extend-via-metadata true
  (-find [this k]
    "returns a vector of k, v.
     Introduce a new protocol rather than using existing interface
     ILookup in order to let us to extend this in the future"))

(extend-protocol Findable
  clojure.lang.ILookup
  (-find [this k]
    (when-let [v (.valAt this k)]
      [k v])))

(comment
 (-find {:a 1} :a))

(defn join?
  "predict if ptn is a join group"
  [ptn]
  (and (map? ptn) (= 1 (count ptn))))

(defn seq-set? [x]
  (or (sequential? x) (set? x)))

(defn apply-seq
  "apply f to x when x is sequential and (pred x) is true, or simply
   apply f to x"
  [pred f x]
  (if (and (seq-set? x) (pred x))
    (mapv f x)
    (f x)))

(def findable?
  (partial satisfies? Findable))

(def find-apply
  (partial apply-seq (partial every? findable?)))

(defn findable-seq?
  [v]
  (and (seq-set? v) (every? findable? v)))

(defn all-findable? [v]
  (or (findable? v) (findable-seq? v)))

(defn pull*
  [data ptn]
  (reduce
   (fn [acc k]
     (if (join? k)
       (let [[local-k sub-ptn] (first k)
             [k sub-data] (-find data local-k)]
         (when k
           (conj acc [k (find-apply #(pull* % sub-ptn) sub-data)])))
       (if-let [[k v] (-find data k)]
         ;;for pullable sequence or value, a join is required
         (conj acc [k (if (all-findable? v)
                        :robertluo.pull/join-required
                        v)])
         acc)))
   {}
   ptn))

(defn pull
  [data ptn]
  (find-apply #(pull* % ptn) data))

(defn private-attrs
  [pred m]
  (with-meta m
    {`-find
     (fn [this k]
       (when-let [v (get this k)]
         (when (not (pred k))
           [k v])))}))

(comment
  (pull {:a [{:aa 3} {:aa 5} {:ab 6}]
         :b {:bb :foo}
         :c 5}
        [{:a [:aa]} {:b [:bb]} :c])
  (pull [{:a 3} {:a 4} {:b 5}] [:a])
  (pull #{{:a 3} {:a 5}} [:a])
  (pull {:a 3 :b {:c "foo" :d :now}} [:a])
  )

;;=============================
;; TODO new pull implementation
;; 
(defn construct
  [xf entries]
  (transduce xf 
             (fn
               ([acc] acc)
               ([acc [ks v]]
                (assoc-in acc ks v)))
             {} entries))

(declare pattern->paths)

(defn elem-of
 [root elem]
 (if (join? elem)
   (let [[k v] (first elem)]
     #(pattern->paths (conj root k) v))
   [(conj root elem)]))

(defn pattern->paths
  [root pattern]
  (mapcat #(trampoline elem-of root %) pattern))

(defn seq-get
  [x k not-found]
  (if (every? findable? x)
    (mapv #(get % k not-found) x)
    (get x k not-found)))

(defn seq-get-in
  "Returns the value in a nested associative structure,
  where ks is a sequence of keys. Returns nil if the key
  is not present, or the not-found value if supplied."
  ([m ks]
   (loop [sentinel (Object.)
          m m
          ks (seq ks)]
     (if ks
       (let [m (seq-get m (first ks) sentinel)]
         (when-not (identical? sentinel m)
           (recur sentinel m (next ks))))
       m))))

(defn select-paths
  [data paths]
  (construct (map (fn [path]
                    [path (get-in data path)])) paths))

(defn pull2
  [data pattern]
  (select-paths data (pattern->paths [] pattern)))