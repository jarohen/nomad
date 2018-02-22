(ns nomad.temp-080-beta
  (:require [nomad.temp-080-beta.loader :refer [load-config]]
            [nomad.temp-080-beta.readers]))

(defn read-config [file-or-resource & [{:keys [cached-config location nomad/secret-keys]}]]
  (load-config {:config-source file-or-resource
                :location location
                :cached-config cached-config
                :nomad/secret-keys secret-keys}))

(def ^:dynamic *location-override* {})

(defmacro with-location-override [override-map & body]
  `(binding [*location-override* ~override-map]
     ~@body))

(defn make-config-cache [file-or-resource {:keys [data-readers]}]
  (let [!cached-config (atom nil)]
    (fn []
      (swap! !cached-config
             (fn [cached-config]
               (binding [*data-readers* (merge *data-readers* data-readers)]
                 (read-config file-or-resource
                              {:cached-config cached-config
                               :location *location-override*})))))))

(defmacro defconfig [name file-or-resource & [{:keys [data-readers] :as opts}]]
  `(def ~name
     (make-config-cache ~file-or-resource ~opts)))
