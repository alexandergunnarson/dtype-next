(ns tech.v3.datatype.bitmap
  "Functions for working with RoaringBitmaps.  These are integrated deeply into
  several tech.v3.datatype algorithms and have many potential applications in
  high performance computing applications as they are both extremely fast and
  storage-space efficient."
  (:require [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.datatype.base :as dtype-base]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.pprint :as dtype-pp]
            [tech.v3.datatype.clj-range :as clj-range]
            [tech.v3.datatype.copy-make-container :as dtype-cmc]
            [tech.v3.parallel.for :as parallel-for]
            [tech.v3.datatype.array-buffer]
            [clojure.core.protocols :as cl-proto]
            [ham-fisted.api :as hamf]
            [ham-fisted.lazy-noncaching :as lznc]
            [ham-fisted.protocols :as hamf-proto]
            [ham-fisted.set :as set])
  (:import [org.roaringbitmap RoaringBitmap IntConsumer]
           [tech.v3.datatype SimpleLongSet LongReader LongBitmapIter Buffer]
           [tech.v3.datatype.array_buffer ArrayBuffer]
           [ham_fisted Transformables]
           [clojure.lang LongRange IFn$OLO IFn$ODO IDeref]
           [java.lang.reflect Field]))


(set! *warn-on-reflection* true)


(def ^{:private true} int-array-class (Class/forName "[I"))


(defn- ensure-int-array
  ^ints [item]
  (when-not (instance? int-array-class item)
    (let [ary-buf (dtype-cmc/make-container :uint32 item)]
      (.ary-data ^ArrayBuffer (dtype-proto/->array-buffer ary-buf)))))


(defn- reduce-into-bitmap
  ^RoaringBitmap [data]
  (set/unique {:set-constructor #(RoaringBitmap.)} data))


(defn- range->bitmap
  [item]
  (let [r (dtype-proto/->range item {})
        rstart (long (dtype-proto/range-start item))
        rinc (long (dtype-proto/range-increment item))
        rend (+ rstart (* rinc (dtype-base/ecount item)))]
    (cond
      (== rstart rend)
      (RoaringBitmap.)
      (== 1 rinc)
      (doto (RoaringBitmap.)
        (.add rstart rend))
      :else
      (reduce-into-bitmap r))))


(declare ->bitmap)


(defn as-range
  "If this is convertible to a long range, then return a range else return nil."
  [bm]
  (when-let [^RoaringBitmap bm (dtype-proto/as-roaring-bitmap bm)]
    (if (.isEmpty bm)
      (hamf/range 0)
      (let [start (Integer/toUnsignedLong (.first bm))
            end (unchecked-inc (Integer/toUnsignedLong (.last bm)))]
        (when (.contains bm start end)
          (hamf/range start end))))))


(defn ->random-access
  "Bitmaps do not implement efficient random access although we do provide access of
  inefficient random access for them.  This converts a bitmap into a flat buffer of data
  that does support efficient random access."
  [bitmap]
  (set/->integer-random-access bitmap))


(deftype ^:private IntReduceConsumer [^:unsynchronized-mutable acc
                                      ^:unsynchronized-mutable store-first
                                      ^IFn$OLO rfn]
  IntConsumer
  (accept [this v]
    (when-not (reduced? acc)
      (set! acc (if store-first
                  (Integer/toUnsignedLong v)
                  (.invokePrim rfn acc (Integer/toUnsignedLong v))))
      (set! store-first false)))
  IDeref
  (deref [this] acc))


(defn- bitmap-reduce
  ([^RoaringBitmap bm rfn acc]
   (let [c (IntReduceConsumer. acc false (Transformables/toLongReductionFn rfn))]
     ;;roaring bitmap explicity states to use forEach
     (when-not (reduced? acc)
       (.forEach bm c))
     @c))
  ([^RoaringBitmap bm rfn]
   (let [c (IntReduceConsumer. nil true (Transformables/toLongReductionFn rfn))]
     ;;roaring bitmap explicity states to use forEach
     (if (.isEmpty bm)
       (rfn)
       (.forEach bm c))
     @c)))


(extend-type RoaringBitmap
  dtype-proto/PElemwiseDatatype
  (elemwise-datatype [bitmap] :uint32)
  dtype-proto/PDatatype
  (datatype [bitmap] :datatype)
  dtype-proto/PECount
  (ecount [bitmap] (.getLongCardinality bitmap))
  dtype-proto/PToReader
  (convertible-to-reader? [bitmap] true)
  (->reader [bitmap] (->random-access bitmap))
  dtype-proto/PConstantTimeMinMax
  (has-constant-time-min-max? [bitmap] (not (.isEmpty bitmap)))
  (constant-time-min [bitmap] (Integer/toUnsignedLong (.first bitmap)))
  (constant-time-max [bitmap] (Integer/toUnsignedLong (.last bitmap)))
  dtype-proto/PRangeConvertible
  (convertible-to-range? [item] (or (.isEmpty item)
                                    (.contains item
                                               (Integer/toUnsignedLong (.first item))
                                               (Integer/toUnsignedLong (.last item)))))
  (->range [item options] (as-range item))
  dtype-proto/PClone
  (clone [bitmap] (.clone bitmap))
  dtype-proto/PToBitmap
  (convertible-to-bitmap? [item] true)
  (as-roaring-bitmap [item] item)
  hamf-proto/PAdd
  (add-fn [lhs] (hamf/long-accumulator
                 acc v (.add ^RoaringBitmap acc (unchecked-int v)) acc))
  hamf-proto/SetOps
  (set? [lhs] true)
  (intersection [lhs rhs] (RoaringBitmap/and lhs (->bitmap rhs)))
  (difference [lhs rhs] (RoaringBitmap/andNot lhs (->bitmap rhs)))
  (union [lhs rhs] (RoaringBitmap/or lhs (->bitmap rhs)))
  (xor [lhs rhs] (RoaringBitmap/xor lhs (->bitmap rhs)))
  (contains-fn [lhs] (hamf/long-predicate v (.contains lhs (unchecked-int v))))
  (cardinality [lhs] (.getCardinality lhs))
  hamf-proto/BitSet
  (bitset? [lhs] true)
  (contains-range? [lhs sidx eidx]
    (let [sidx (long sidx)
          eidx (long eidx)]
      (if (< sidx 0) false
          (.contains lhs sidx eidx))))
  (intersects-range? [lhs sidx eidx]
    (let [sidx (max 0 (long sidx))
          eidx (max 0 (long eidx))]
      (if (== sidx eidx)
        false
        (.intersects lhs sidx eidx))))
  (min-set-value [lhs] (Integer/toUnsignedLong (.first lhs)))
  (max-set-value [lhs] (Integer/toUnsignedLong (.last lhs)))
  hamf-proto/Reduction
  (reducible? [this] true)
  cl-proto/CollReduce
  (coll-reduce
    ([this rfn acc] (bitmap-reduce this rfn acc))
    ([this rfn] (bitmap-reduce this rfn))))


(dtype-pp/implement-tostring-print RoaringBitmap)


(casting/add-object-datatype! :bitmap RoaringBitmap false)


(defn ->bitmap
  "Create a roaring bitmap.  If this object has a conversion to a roaring bitmap use
  that, else copy the data into a new roaring bitmap."
  (^RoaringBitmap [item]
   (cond
     (nil? item)
     (RoaringBitmap.)
     (instance? RoaringBitmap item) item
     (dtype-proto/convertible-to-bitmap? item)
     (dtype-proto/as-roaring-bitmap item)
     (dtype-proto/convertible-to-range? item)
     (range->bitmap (dtype-proto/->range item nil))
     :else
     (reduce-into-bitmap item)))
  (^RoaringBitmap []
   (RoaringBitmap.))
  (^RoaringBitmap [^long sidx ^long eidx]
   (range->bitmap (hamf/range sidx eidx))))


(defn offset
  "Offset a bitmap creating a new bitmap."
  ^RoaringBitmap[^RoaringBitmap bm ^long offset]
  (RoaringBitmap/addOffset bm (unchecked-int offset)))


(defn ->unique-bitmap
  "Perform a conversion to a bitmap.  If this thing is already a bitmap, clone it."
  (^RoaringBitmap [item]
   (if (dtype-proto/convertible-to-bitmap? item)
     (.clone ^RoaringBitmap (dtype-proto/as-roaring-bitmap item))
     (->bitmap item)))
  (^RoaringBitmap []
   (RoaringBitmap.)))


(extend-protocol hamf-proto/BulkSetOps
  RoaringBitmap
  (reduce-union [l data]
    (reduce (fn [^RoaringBitmap acc data]
              (.or acc (->bitmap data))
              acc)
            (.clone l)
            data))

  (reduce-intersection [l data]
    (reduce (fn [^RoaringBitmap acc data]
              (.and acc (->bitmap data))
              acc)
            (.clone l)
            data)))


(defn reduce-union
  "Reduce a sequence of bitmaps into a single bitmap via union"
  ^RoaringBitmap [bitmaps]
  (apply set/reduce-union bitmaps))


(defn reduce-intersection
  "Reduce a sequence of bitmaps into a single bitmap via intersection"
  ^RoaringBitmap [bitmaps]
  (apply set/reduce-intersection bitmaps))


(defn bitmap-value->map
  "Given a bitmap and a value return a map of each bitmap index to that value."
  [bitmap val]
  (->> bitmap
       (lznc/map #(hamf/vector % val))
       (hamf/immut-map)))
