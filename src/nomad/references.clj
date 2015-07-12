(ns nomad.references
  (:require [clojure.walk :refer [prewalk]]
            [medley.core :as m]))

(defprotocol ResolvableReference
  (resolve-value [_ config]))

(defn resolve-references [config {:keys [location]}]
  (loop [config config
         n 5]

    (when-not (pos? n)
      (throw (ex-info "Too many nested references. (A loop, maybe?)" {})))

    (let [config-with-location-meta (when config
                                      (with-meta config
                                        {:nomad/location location}))
          !unresolved-references? (atom false)
          new-config (prewalk (fn [obj]
                                (if (satisfies? ResolvableReference obj)
                                  (do
                                    (reset! !unresolved-references? true)
                                    (resolve-value obj config-with-location-meta))

                                  obj))
                              config)]

      (if @!unresolved-references?
        (recur new-config (dec n))
        new-config))))
