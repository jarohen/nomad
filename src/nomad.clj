(ns nomad
  (:require [nomad.loader :refer [load-config]]
            [nomad.readers]))

(defn read-config [file-or-resource & [{:keys [cached-config location]}]]
  (load-config {:config-source file-or-resource
                :location location
                :cached-config cached-config}))

(def ^:dynamic *location-override* {})

(defmacro with-location-override [override-map & body]
  `(binding [*location-override* ~override-map]
     ~@body))

(defmacro defconfig [name file-or-resource & [{:keys [data-readers]}]]
  `(let [!cached-config# (atom nil)]
     (defn ~name []
       (swap! !cached-config#
              (fn [cached-config#]
                (binding [*data-readers* (merge *data-readers* data-readers)]
                  (read-config ~file-or-resource
                               {:cached-config cached-config#
                                :location *location-override*})))))))
