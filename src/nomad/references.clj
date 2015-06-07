(ns nomad.references
  (:require [clojure.walk :refer [postwalk]]))

(defprotocol ResolvableReference
  (resolve-value [_ config]))

(defn resolve-references [config]
  ;; TODO: proper infinite loop checking, rather than assuming there
  ;; isn't going to be a path of length > 5 (about 8 blows the stack,
  ;; on my machine)

  (loop [config config
         n 5]
    (when-not (pos? n)
      (throw (ex-info "Too many nested references. (A loop, maybe?)" {})))

    (let [!unresolved-references? (atom false)
          new-config (postwalk (fn [obj]
                                 (if (satisfies? ResolvableReference obj)
                                   (do
                                     (reset! !unresolved-references? true)
                                     (resolve-value obj config))

                                   obj))
                               config)]


      (if @!unresolved-references?
        (recur new-config (dec n))
        new-config))))
