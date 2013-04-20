(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def ^:private get-hostname
  (memoize
   (fn []
     (.trim (:out (sh "hostname"))))))

(def ^:private get-instance
  (memoize
   (fn []
     (get (System/getenv) "NOMAD_INSTANCE" :default))))

(defprotocol ConfigFile
  (etag [_])
  (slurp* [_]))

(extend-protocol ConfigFile
  java.io.File
  (etag [f] {:file f
             :last-mod (.lastModified f)})
  (slurp* [f] (slurp f))

  java.net.URL
  (etag [url]
    (case (.getProtocol url)
      "file" (etag (io/as-file url))

      ;; otherwise, we presume the config file is read-only
      ;; (i.e. in a JAR file)
      {:url url}))

  (slurp* [url] (slurp url))

  nil
  (etag [_] nil)
  (slurp* [_] (pr-str {})))

(defn- reload-config-file [config-file]
  (binding [*data-readers* (assoc *data-readers*
                             'nomad/file io/file)]
    (with-meta (read-string (slurp* config-file))
      {:old-etag (etag config-file)
       :config-file config-file})))

(defn- update-config-file [current-config]
  (let [{:keys [old-etag config-file]} (meta current-config)
        new-etag (etag config-file)]
    (if (not= old-etag new-etag)
      (reload-config-file config-file)
      current-config)))

(defn- with-current-host-config [config]
  (assoc config
    :nomad/current-host (or (get-in config [:nomad/hosts (get-hostname)])
                            {})))

(defn- with-current-instance-config [config]
  (assoc config
    :nomad/current-instance (or (get-in config [:nomad/current-host
                                                :nomad/instances
                                                (get-instance)])
                                {})))

(defn- with-updated-private-config [config]
  (update-in
   config [:nomad/private]

   (fn [private-config]
     (letfn [(update-private-config [k]
               (update-config-file
                (or (get private-config k)
                    (with-meta {}
                      {:old-etag ::nil
                       :config-file (get-in config [k :nomad/private-file])}))))]
                 
       {:nomad/current-host (update-private-config :nomad/current-host)
        :nomad/current-instance (update-private-config :nomad/current-instance)}))))

(defn- update-config [current-config]
  (-> (update-config-file current-config)
      with-current-host-config
      with-current-instance-config
      with-updated-private-config))

(defn- get-current-config [config-ref]
  (dosync (alter config-ref update-config)))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref (with-meta {}
                            {:config-file ~file-or-resource
                             :old-etag ::nil}))]
     (defn ~name []
       (#'get-current-config config-ref#))))
