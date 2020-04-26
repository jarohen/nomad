(ns nomad.secrets
  (:require [clojure.tools.reader.edn :as edn]
            [buddy.core.codecs :as bc]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.crypto :as b]
            [buddy.core.nonce :as bn]))

(def ^:private ^:dynamic *secret-keys* {})

(defn generate-key []
  (bc/bytes->str (b64/encode (bn/random-bytes 32))))

(def ^:private block-size
  (b/block-size (b/block-cipher :aes :cbc)))

(defn- resolve-secret-key [secret-key]
  (-> (cond
        (string? secret-key) secret-key
        (keyword? secret-key) (or (get *secret-keys* secret-key)
                                  (throw (ex-info "missing secret-key" {"secret-key" secret-key})))
        :else (throw (ex-info "invalid secret-key" {:secret-key secret-key})))
      bc/str->bytes
      b64/decode))

(defn encrypt [secret-key plain-obj]
  (let [iv (bn/random-bytes block-size)]
    (->> [iv (-> (pr-str plain-obj)
                 bc/str->bytes
                 (b/encrypt (resolve-secret-key secret-key) iv))]
         (mapcat seq)
         byte-array
         b64/encode
         bc/bytes->str)))

(defn decrypt [secret-key cipher-text]
  (let [[iv cipher-bytes] (map byte-array (split-at block-size (b64/decode cipher-text)))]
    (-> cipher-bytes
        (b/decrypt (resolve-secret-key secret-key) iv)
        edn/read-string)))

(defn set-default-keys! [secret-keys]
  (alter-var-root *secret-keys* (constantly secret-keys)))

(defn with-secret-keys* [secret-keys f]
  (binding [*secret-keys* (merge *secret-keys* secret-keys)]
    (f)))

(defmacro with-secret-keys [secret-keys & body]
  `(with-secret-keys* ~secret-keys (fn [] ~@body)))
