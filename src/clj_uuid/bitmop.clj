(ns clj-uuid.bitmop
  (:refer-clojure :exclude [* + - / < > <= >= == rem bit-or bit-and bit-xor
                            bit-not bit-shift-left bit-shift-right
                            byte short int float long double inc dec
                            zero? min max true? false?])
  (:require [primitive-math :refer :all]
            [clojure.pprint :refer [cl-format pprint]]
            ;; [clojure.core.reducers :as r]
            [clj-uuid.constants :refer :all]
            [clj-uuid.util :refer :all]))


(set! *warn-on-reflection* true)

;; Primitive Type  |  Size   |  Minimum  |     Maximum    |  Wrapper Type
;;-----------------------------------------------------------------------
;; boolean         |1?8 bits |   false   |     true       |  Boolean
;; char            | 16 bits | Unicode 0 | Unicode 2^16-1 |  Character
;; byte            |  8 bits |  -128     |     +127       |  Byte
;; short           | 16 bits |  -2^15    |     +2^15-1    |  Short
;; int             | 32 bits |  -2^31    |     +2^31-1    |  Integer
;; long            | 64 bits |  -2^63    |     +2^63-1    |  Long
;; float           | 32 bits |  IEEE754  |     IEEE754    |  Float
;; double          | 64 bits |  IEEE754  |     IEEE754    |  Double
;; void            |    ?    |     ?     |        ?       |  Void


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Simple Arithmetic Utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; {:pre [(not (neg? pow)) (< pow 64)]}

(defn- ^long expt2 [^long pow]
  (bit-set 0 pow))

(defn pphex [x]
  (returning x
    (cl-format *out* "~&[~A] [~64,,,'0@A]~%"
      (format "%1$016X" x)
      (Long/toBinaryString x))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bit-masking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; {:pre [(not (neg? width)) (not (neg? offset))
;;        (<= width 64) (< offset 64)]}

(defn mask [^long width ^long offset]
  (if (< (+ width offset) 64)
    (bit-shift-left (dec (bit-shift-left 1 width)) offset)
    (bit-and-not -1 (clojure.core/dec (expt2 offset)))))

(declare mask-offset mask-width)

(defn mask-offset [^long m]
  (cond
    (zero? m) 0
    (neg?  m) (clojure.core/- 64 (mask-width m))
    :else     (loop [c 0]
                (if (pos? (bit-and 1 (bit-shift-right m c)))
                  c
                  (recur (inc c))))))

(defn mask-width [^long m]
  (if (neg? m)
    (clojure.core/- 64 (mask-width (- (inc m))))
    (loop [m (bit-shift-right m (mask-offset m)) c 0]
      (if (zero? (bit-and 1 (bit-shift-right m c)))
        c
        (recur m (inc c))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fundamental Bitwise Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^long ldb [^long bitmask ^long num]
  (let [off (mask-offset bitmask)]
    (bit-and (>>> bitmask ^long off)
      (bit-shift-right num off))))

(defn ^long dpb [^long bitmask ^long num ^long value]
  (bit-or (bit-and-not num bitmask)
    (bit-and bitmask
      (bit-shift-left value (mask-offset bitmask)))))

(defn ^long bit-count [^long x]
  (let [n (ldb (mask 63 0) x) s (if (neg? x) 1 0)]
    (loop [c s i 0]
      (if (zero? (bit-shift-right n i))
        c
        (recur (+ c (bit-and 1 (bit-shift-right n i))) (inc i))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Byte Casting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ub4 [num]
  (byte (bit-and num +ub4-mask+)))

(defn ub8 [^long num]
  (unchecked-short (bit-and num +ub8-mask+)))

;; (defn ub8 [num]
;;   (bit-and num +ub8-mask+))
;; (type (ub8 22))
;; (type (bit-and +ub8-mask+ (unchecked-short 22)))
;; (type (bit-and +ub8-mask+ (Short. (short 22))))

(defn ub16 [num]
  (int (bit-and num +ub16-mask+)))

(defn ub24 [num]
  (int (bit-and num +ub24-mask+)))

(defn ub32 [num]
  (long (bit-and num +ub32-mask+)))

(defn ub48 [num]
  (long (bit-and num +ub48-mask+)))

(defn ub56 [num]
  (long (bit-and num +ub56-mask+)))

(defn sb8 [num]
  (unchecked-byte (ub8 num)))

(defn sb16 [num]
  (unchecked-short (ub16 num)))

(defn sb32 [num]
  (unchecked-int (ub32 num)))

(defn sb64 [num]
  (unchecked-long num))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; "Byte Vectors"
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; implement a collection of primitive signed and unsigned byte
;; values cast appropriately from the JVM native (signed) two's complement
;; representation.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn assemble-bytes [v]
  (loop [tot 0 bytes v c (count v)]
    (if (zero? c)
      tot
      (recur
        (long (dpb (mask 8 (* 8 (dec c))) tot ^long (first bytes)))
        (rest bytes)
        (dec c)))))


;; (defn assemble-bytes [v]
;;  (reduce (fn [^long tot ^clojure.lang.MapEntry pair]
;;            (dpb (mask 8 (* ^long (.key pair) 8)) tot ^long (.val pair)))
;;    0 (indexed (reverse v))))

;; (defn assemble-bytes [v]
;;   (r/reduce (fn
;;               ([] 0)
;;               ([tot ^clojure.lang.MapEntry pair]
;;                (dpb (mask 8 (* ^Long (.key pair) 8)) tot (.val pair))))
;;     (indexed (reverse v))))

(defn long-to-octets
  "convert a long into a sequence of minimum PAD-COUNT unsigned values.
  The zeroes are padded to the msb"
  ([^long lng]
    (long-to-octets lng 8))
  ([^long lng pad-count]
    (let [pad         (repeat pad-count (byte 0))
          raw-bytes   (for [^byte i (range 8)] (ldb (mask 8 (* i 8)) lng))
          value-bytes (drop-while clojure.core/zero? (reverse raw-bytes))]
      (vec (concat
             (into [] (drop (count value-bytes) pad))
             value-bytes)))))

(defn sbvec [thing]
  (cond 
    (= (class thing) Long) (into (vector-of :byte)
                             (map unchecked-byte (long-to-octets thing)))
    (coll? thing)          (into (vector-of :byte)
                             (map unchecked-byte thing))
    :else                  (exception IllegalArgumentException)))

(defn sbvector [& args]
  (sbvec args))

(defn make-sbvector [^long length initial-element]
  (sbvec (loop [len length v []]
           (if (<= len 0)
             v
             (recur (- len 1)
               (cons (unchecked-byte initial-element) v))))))

(defn ubvec [thing]
  (cond
   (= (class thing) Long) (into (vector-of :short)
                            (map unchecked-short (long-to-octets thing)))
   (coll? thing)          (into (vector-of :short)
                            (map unchecked-short thing))
   :else                  (exception IllegalArgumentException)))

(defn ubvector [& args]
  (ubvec args))

(defn make-ubvector [^long length initial-element]
  (ubvec (loop [len length v []]
           (if (<= len 0)
             v
             (recur (- len 1)
               (cons (short initial-element) v))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Hexadecimal String Representation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn octet-hex [num]
  (str
    (+hex-chars+ (bit-shift-right num 4))
    (+hex-chars+ (bit-and 0x0F num))))

(defn hex [thing]
  (cond 
    (and (number? thing) (<  ^long thing 0))  (hex (ubvec thing))
    (and (number? thing) (>= ^long thing 0))  (hex (ubvec thing))
    (coll? thing)   (apply str (into [] (map octet-hex thing)))))

(defn hex-str [^String s]
  (hex (.getBytes s)))

(defn unhex [^String s]
  (unchecked-long (read-string (str "0x" s))))

(defn unhex-str [^String s]
  (apply str (map char (unhex s))))


(set! *warn-on-reflection* false)
