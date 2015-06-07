(ns nomad.secret
  (:require [buddy.core.codecs :as bc]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]
            [clojure.tools.reader.edn :as edn]))

(defn- calculate-padding-length [{:keys [byte-count block-size]}]
  (mod (- block-size
          (mod (inc byte-count)
               block-size))
       block-size))

(defn- pad [bytes block-size]
  (let [byte-count (count bytes)
        padding-length (calculate-padding-length {:byte-count byte-count
                                                  :block-size block-size})
        out-array (byte-array (+ byte-count padding-length 1))]
    (aset out-array 0 (byte padding-length))
    (System/arraycopy bytes 0 out-array 1 byte-count)
    (System/arraycopy (bn/random-bytes padding-length) 0 out-array (inc byte-count) padding-length)

    out-array))

(defn- unpad [bytes block-size]
  (let [padding-length (aget bytes 0)
        byte-count (- (alength bytes) padding-length 1)
        out-array (byte-array byte-count)]
    (System/arraycopy bytes 1 out-array 0 byte-count)
    out-array))

(defn- make-cypher []
  (b/block-cipher :aes :ofb))

(defn- make-iv [block-size]
  (bn/random-bytes block-size))

(defn generate-key
  ([] (generate-key 256))

  ([key-size-bits]
   (bc/bytes->hex (bn/random-bytes (/ key-size-bits 8)))))

(defn decrypt [cypher-text secret-key]
  (let [cypher (make-cypher)
        block-size (.getBlockSize cypher)
        [iv cypher-bytes] (map byte-array (split-at block-size (bc/hex->bytes cypher-text)))
        plain-bytes (byte-array (count cypher-bytes))]

    (b/initialize! cypher
                   {:key (bc/hex->bytes secret-key)
                    :iv iv
                    :op :decrypt})

    (doseq [block-idx (range 0 (count cypher-bytes) block-size)]
      (.processBlock cypher cypher-bytes block-idx plain-bytes block-idx))

    (edn/read-string (String. (unpad plain-bytes block-size) "utf-8"))))

(defn encrypt [plain-obj secret-key]
  (let [cypher (make-cypher)
        block-size (.getBlockSize cypher)
        iv (make-iv block-size)

        obj-bytes (pad (.getBytes (pr-str plain-obj) "utf-8") (.getBlockSize cypher))
        cypher-bytes (byte-array (count obj-bytes))]

    (b/initialize! cypher
                   {:key (bc/hex->bytes secret-key)
                    :iv iv
                    :op :encrypt})

    (doseq [block-idx (range 0 (count obj-bytes) block-size)]
      (.processBlock cypher obj-bytes block-idx cypher-bytes block-idx))

    (str (bc/bytes->hex iv)
         (bc/bytes->hex cypher-bytes))))



(comment
  (let [foo-key (generate-key)]
    (-> {:a 1 :b 2}
        (encrypt foo-key)
        (doto prn)
        (decrypt foo-key))))
