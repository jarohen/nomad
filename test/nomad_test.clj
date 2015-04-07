(ns nomad-test
  (:require [nomad :refer [defconfig read-config]]
            [clojure.test :as test :refer [deftest]]
            [clojure.java.io :as io]))

;; TODO formal environment tests

(defmacro with-hostname [hostname & body]
  `(with-redefs [nomad/get-hostname (constantly ~hostname)]
     ~@body))

(defmacro with-instance [instance & body]
  `(with-redefs [nomad/get-instance (constantly ~instance)]
     ~@body))

(defmacro with-environment [environment & body]
  `(with-redefs [nomad/get-environment (constantly ~environment)]
     ~@body))

(defrecord DummyConfigFile [_etag _content]
  nomad/ConfigFile
  (etag [_] (_etag))
  (slurp* [_] (_content)))

(deftest loads-simple-config
  (let [config {:my-key :my-val}
        {:keys [etag config]} (#'nomad/update-config-file {}
                                                          (DummyConfigFile.
                                                           (constantly :etag)
                                                           (constantly (pr-str config))))]
    (test/is (= :my-val (:my-key config)))
    (test/is (= :etag etag))))

(deftest caches-when-not-changed
  (let [config {:a 1 :b 2}
        constant-etag "the-etag"
        dummy-config-file (DummyConfigFile. (constantly constant-etag)
                                            #(throw (AssertionError.
                                                     "Shouldn't reload!")))
        returned-config (#'nomad/update-config-file
                         {:etag constant-etag
                          :config config}
                         dummy-config-file)]
    (test/is (= config (select-keys (:config returned-config) [:a :b])))))

(deftest reloads-when-changed
  (let [old-config {:a 1 :b 2}
        new-config (assoc old-config :a 3)
        dummy-config-file (DummyConfigFile. (constantly "new-etag")
                                            (constantly (pr-str new-config)))
        returned-config (#'nomad/update-config-file
                         {:etag "old-etag"
                          :config old-config}
                         dummy-config-file)]
    (test/is (= new-config (select-keys (:config returned-config) [:a :b])))))

(deftest host-config
  (let [config {:nomad/hosts {"my-host" {:a 1}
                              "not-my-host" {:a 2}}}
        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly
                                             (pr-str config)))
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           {:general {:config-file dummy-config-file}}))]
    (test/is (= 1 (get-in returned-config [:host :config :a])))))

(deftest instance-config
  (let [config {:nomad/hosts {"my-host"
                              {:nomad/instances
                               {"DEV1" {:a 1}
                                "DEV2" {:a 2}}}
                              "not-my-host" {:a 2}}}
        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly
                                             (pr-str config)))
        returned-config (with-hostname "my-host"
                          (with-instance "DEV2"
                            (#'nomad/update-config
                             {:general {:config-file dummy-config-file}})))]
    (test/is (= 2 (get-in returned-config [:instance :config :a])))))

(deftest merges-public-config
  (let [config-map {:general
                    {:config {:a :general
                              :b {:b1 :general
                                  :b2 :general
                                  :b3 :general}
                              :c :general
                              :d :general}}

                    :host
                    {:config {:c :host
                              :b {:b2 :host
                                  :b3 :host}}}

                    :instance
                    {:config {:d :instance
                              :b {:b3 :instance}}}

                    :location
                    {:nomad/hostname :hostname
                     :nomad/instance :instance-name}}
        merged-config (#'nomad/merge-configs config-map)]
    (test/is (= :general (get-in merged-config [:a])))
    (test/is (= :general (get-in merged-config [:b :b1])))
    (test/is (= :host (get-in merged-config [:b :b2])))
    (test/is (= :instance (get-in merged-config [:b :b3])))
    (test/is (= :host (get-in merged-config [:c])))
    (test/is (= :instance (get-in merged-config [:d])))
    (test/is (= :hostname (get-in merged-config [:nomad/hostname])))
    (test/is (= :instance-name (get-in merged-config [:nomad/instance])))))

(deftest adds-hostname-key-to-map
  (let [returned-config (with-hostname "dummy-hostname"
                          (#'nomad/update-config
                           {:general {:config-file (DummyConfigFile.
                                                    (constantly ::etag)
                                                    (constantly (pr-str {})))}}))]
    (test/is (= "dummy-hostname" (get-in returned-config [:location :nomad/hostname])))))

(deftest adds-instance-key-to-map
  (let [returned-config (with-instance "dummy-instance"
                          (#'nomad/update-config
                           {:general {:config-file (DummyConfigFile.
                                                    (constantly ::etag)
                                                    (constantly (pr-str {})))}}))]
    (test/is (= "dummy-instance" (get-in returned-config [:location :nomad/instance])))))

(deftest loads-default-env-vars
  (let [{:keys [etag config]}
        (#'nomad/update-config-file {}
                                    (DummyConfigFile.
                                     (constantly :etag)
                                     (constantly "{:test #nomad/env-var [\"NOPE\" :default]}")))]
    (test/is (= :default (:test config)))))

(deftest loads-default-edn-env-vars
  (let [{:keys [etag config]}
        (#'nomad/update-config-file {}
                                    (DummyConfigFile.
                                     (constantly :etag)
                                     (constantly "{:test #nomad/edn-env-var [\"NOPE\" :default]}")))]
    (test/is (= :default (:test config)))))

(defrecord DummyPrivateFile [etag* content*]
  nomad/ConfigFile
  (etag [_] etag*)
  (slurp* [_] (pr-str content*)))

(deftest loads-private-config
  (let [config {:nomad/private-file
                (DummyPrivateFile.
                 ::etag {:something-private :yes-indeed})}
        dummy-private-file (DummyPrivateFile.
                            ::etag {:something-private :yes-indeed})
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           {:host
                            {:config {:nomad/private-file dummy-private-file}
                             :selector-value "my-host"}}))]
    (test/is (= :yes-indeed (get-in returned-config [:host-private :config :something-private])))))

(deftest deep-merges-private-config
  (let [config {:host
                {:config
                 {:database {:username "my-user"
                             :password "failed..."}}}
                :host-private
                {:config {:database {:password "password123"}}}}
        returned-config (#'nomad/merge-configs config)]
    (test/is (= "my-user" (get-in returned-config [:database :username])))
    (test/is (= "password123" (get-in returned-config [:database :password])))))

(deftest reloads-private-config-when-private-file-changes
  (let [new-private-file (DummyPrivateFile.
                          "new-etag" {:private-key :yes-indeed})
        new-config {:nomad/private-file new-private-file}
        returned-config (#'nomad/update-config
                         {:instance
                          {:config new-config
                           :selector-value :default}

                          :instance-private
                          {:config {:private-key :definitely-not}
                           :etag "old-etag"}})]
    (test/is (= :yes-indeed
                (get-in returned-config
                        [:instance-private :config :private-key])))))

(defrecord DummyUnchangingPrivateFile [etag*]
  nomad/ConfigFile
  (etag [_] etag*)
  (slurp* [_] (throw (AssertionError. "Shouldn't reload!"))))

(deftest caches-private-config-when-nothing-changes
  (let [private-file (DummyUnchangingPrivateFile. "same-private-etag")
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           {:host
                            {:config {:nomad/private-file private-file}
                             :selector-value "my-host"}

                            :host-private
                            {:config {:private-key :yes-indeed}
                             :etag "same-private-etag"}}))]

    (test/is (= :yes-indeed (get-in returned-config [:host-private :config :private-key])))))

(deftest dereferences-snippet
  (let [config "{:nomad/hosts {\"my-host\"
                               {:database #nomad/snippet [:databases :dev]}}
                 :nomad/snippets {:databases {:dev {:host \"dev-database\"}
                                              :prod {:host \"prod-database\"}}}}"
        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly config))
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           {:general {:config-file dummy-config-file}}))]

    (test/is (= "dev-database" (get-in returned-config [:host :config :database :host])))))

(deftest reflects-overrides
  (let [config {:nomad/environments
                {:default
                 {:test-key-1 "test-value-1"}
                 "override-env"
                 {:test-key-1 "test-value-1-override"}}

                :nomad/hosts
                {"default-host"
                 {:nomad/instances
                  {:default
                   {:test-key-2 "test-value-2"}}}
                 "override-host"
                 {:nomad/instances
                  {"override-instance"
                   {:test-key-2 "test-value-2-override"}}}}}

        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly (pr-str config)))

        default-config (with-hostname "default-host"
                         (with-environment :default
                           (read-config dummy-config-file)))

        override-config (nomad/with-location-override {:environment "override-env"
                                                       :hostname    "override-host"
                                                       :instance    "override-instance"}
                          (read-config dummy-config-file))

        expected-default {:nomad/instance    :default
                          :nomad/hostname    "default-host"
                          :nomad/environment :default
                          :test-key-1        "test-value-1"
                          :test-key-2        "test-value-2"}

        expected-override {:nomad/instance    "override-instance"
                           :nomad/hostname    "override-host"
                           :nomad/environment "override-env"
                           :test-key-1        "test-value-1-override"
                           :test-key-2        "test-value-2-override"}]

    (test/is (= default-config expected-default))
    (test/is (= override-config expected-override))))

(deftest uses-data-readers
  (binding [*data-readers* (assoc *data-readers*
                             'nomad-test/file-reader #(apply io/file %))]
    (test/is (= "/tmp/test.txt"
                (-> (read-config (map->DummyConfigFile {:_etag (constantly ::etag)
                                                        :_content (constantly "{:file #nomad-test/file-reader [\"/tmp/test.txt\"]}")}))
                    :file
                    .getPath)))))

(deftest reads-jvm-prop
  (System/setProperty "nomad-test-property" (pr-str {:a 1 :b 2}))

  (test/is (= (System/getProperty "user.name")
              (:username (read-config "{:username #nomad/jvm-prop \"user.name\"}"))))
  (test/is (= {:a 1 :b 2}
              (:username (read-config "{:username #nomad/edn-jvm-prop \"nomad-test-property\"}")))))

(comment
  ;; This bit is for some manual integration testing

  (defconfig test-config (io/resource "test-config.edn"))

  (time (with-hostname "my-host"
          (with-instance "test-instance"
            (test-config))))

  (with-hostname "my-host"
    (with-instance "env-var-test"
      (test-config))))
