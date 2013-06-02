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
    {:etag (etag config-file)
     :config-file config-file
     :config (read-string (slurp* config-file))}))

(defn- update-config-file [current-config]
  (let [{:keys [config-file]
         old-etag :etag} current-config
        new-etag (etag config-file)]
    (if (not= old-etag new-etag)
      (reload-config-file config-file)
      current-config)))

(defn update-host-config [{:keys [public host] :as current-config}]
  (let [{new-public-etag :etag
         new-public-config :config} public
         
         {old-public-etag :public-etag
          :as current-host-config} host]

    (assoc current-config
      :host (if (= new-public-etag old-public-etag)
              current-host-config
              {:public-etag new-public-etag
               :etag new-public-etag
               :config (get-in new-public-config [:nomad/hosts (get-hostname)])}))))

(defn update-instance-config [{:keys [host instance] :as current-config}]
  (let [{new-host-etag :etag
         new-host-config :config} host
         
         {old-host-etag :host-etag
          :as current-instance-config} instance]
    
    (assoc current-config
      :instance (if (= new-host-etag old-host-etag)
                  current-instance-config
                  {:host-etag new-host-etag
                   :config (get-in new-host-config [:nomad/instances (get-instance)])}))))

(defn add-environment [configs]
  (assoc configs
    :environment {:nomad/hostname (get-hostname)
                  :nomad/instance (get-instance)}))

(defn update-private-config [configs src-key dest-key]
  configs)

(defn- merge-configs [configs]
  (-> (deep-merge (get-in configs [:public :config])
                  (get-in configs [:host :config])
                  (get-in configs [:instance :config])
                  (get-in configs [:environment]))
      (dissoc :nomad/hosts :nomad/instances :nomad/private-file)
      (with-meta configs)))

(defn- update-config [current-config]
  (-> current-config
      (update-in [:public] update-config-file)
      update-host-config
      update-instance-config
      add-environment
      (update-private-config :host :host-private)
      (update-private-config :instance :instance-private)))

(defn- get-current-config [config-ref]
  (dosync
   (-> (alter config-ref update-config)
       merge-configs)))

(defmacro defconfig [name file-or-resource]
  `(let [config-ref# (ref {:public {:config-file ~file-or-resource}})]
     (defn ~name []
       (#'get-current-config config-ref#))))
