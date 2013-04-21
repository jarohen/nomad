(ns nomad
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [nomad.map :refer [deep-merge]]))

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
      (vary-meta (reload-config-file config-file) assoc :updated? true)
      (vary-meta current-config dissoc :updated?))))

(defn- with-updated-private-config [specific-config]
  (let [just-public-config (-> specific-config meta :public-config)
        old-private-config (or (-> specific-config meta :private-config)
                               (with-meta {}
                                 {:config-file (get specific-config :nomad/private-file)}))
        new-private-config (update-config-file old-private-config)]
    (if (-> new-private-config meta :updated?)
      (-> (deep-merge new-private-config just-public-config)
          (vary-meta assoc :private-config new-private-config))
      specific-config)))

(defn- with-current-specific-config [config config-key refresh-specific-config]
  (update-in config
             [config-key]
             (fn [current-config]
               (-> (or current-config
                       (when-let [public-config (refresh-specific-config)]
                         (with-meta public-config
                           {:public-config public-config}))
                       {})
                   with-updated-private-config))))

(defn- with-current-host-config [config]
  (-> config
      (with-current-specific-config :nomad/current-host
        #(get-in config [:nomad/hosts (get-hostname)]))))

(defn- with-current-instance-config [config]
  (-> config
      (with-current-specific-config :nomad/current-instance
        #(get-in config [:nomad/current-host :nomad/instances (get-instance)]))))

(defn- update-config [current-config]
  (-> (update-config-file current-config)
      with-current-host-config
      with-current-instance-config))

(defn- get-current-config [config-ref]
  (dosync (alter config-ref update-config)))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref (with-meta {}
                            {:config-file ~file-or-resource
                             :old-etag ::nil}))]
     (defn ~name []
       (#'get-current-config config-ref#))))
