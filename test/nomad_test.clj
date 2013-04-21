(ns nomad-test
  (:require [nomad :refer [defconfig]]
            [clojure.test :as test :refer [deftest]]))

(defmacro with-hostname [hostname & body]
  `(with-redefs [nomad/get-hostname (constantly ~hostname)]
     ~@body))

(defmacro with-instance [instance & body]
  `(with-redefs [nomad/get-instance (constantly ~instance)]
     ~@body))

(defrecord DummyConfigFile [_etag _content]
  nomad/ConfigFile
  (etag [_] (_etag))
  (slurp* [_] (_content)))

(deftest simple-config
  (let [config {:my-key :my-val}
        {:keys [my-key]} (#'nomad/update-config
                          (with-meta {}
                            {:config-file (DummyConfigFile. (constantly ::etag)
                                                            (constantly
                                                             (pr-str config)))}))]
    (test/is (= :my-val my-key))))

(deftest host-config
  (let [config {:nomad/hosts {"my-host" {:a 1}
                              "not-my-host" {:a 2}}}
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           (with-meta {}
                             {:config-file (DummyConfigFile. (constantly ::etag)
                                                             (constantly
                                                              (pr-str config)))})))]
    (test/is (= 1 (get-in returned-config [:nomad/current-host :a])))))

(deftest instance-config
  (let [config {:nomad/hosts {"my-host"
                              {:nomad/instances
                               {"DEV1" {:a 1}
                                "DEV2" {:a 2}}}
                              "not-my-host" {:a 2}}}
        returned-config (with-hostname "my-host"
                          (with-instance "DEV2"
                            (#'nomad/update-config
                             (with-meta {}
                               {:config-file (DummyConfigFile. (constantly ::etag)
                                                               (constantly
                                                                (pr-str config)))}))))]
    (test/is (= 2 (get-in returned-config [:nomad/current-instance :a])))))

(deftest caches-when-not-changed
  (let [config {:a 1 :b 2}
        constant-etag "the-etag"
        dummy-config-file (DummyConfigFile. (constantly constant-etag)
                                            #(throw (AssertionError.
                                                     "Shouldn't reload!")))
        returned-config (#'nomad/update-config
                         (with-meta config
                           {:old-etag constant-etag
                            :config-file dummy-config-file}))]
    (test/is (= config (select-keys returned-config [:a :b])))))

(deftest reloads-when-changed
  (let [old-config {:a 1 :b 2}
        new-config (assoc old-config :a 3)
        dummy-config-file (DummyConfigFile. (constantly "new-etag")
                                            (constantly (pr-str new-config)))
        returned-config (#'nomad/update-config (with-meta old-config
                                                      {:old-etag "old-etag"
                                                       :config-file dummy-config-file}))]
    (test/is (= new-config (select-keys returned-config [:a :b])))))

(defrecord DummyPrivateFile [etag* content*]
  nomad/ConfigFile
  (etag [_] etag*)
  (slurp* [_] (pr-str content*)))

(deftest loads-private-config
  (let [config {:nomad/hosts {"my-host"
                              {:nomad/private-file
                               (DummyPrivateFile.
                                ::etag {:host-private :yes-indeed})
                               :nomad/instances
                               {"instance" {:a 1
                                            :nomad/private-file
                                            (DummyPrivateFile.
                                             ::etag {:instance-private :of-course})}}}}}
        returned-config (with-hostname "my-host"
                          (with-instance "instance"
                            (#'nomad/update-config
                             (with-meta {}
                               {:config-file (DummyConfigFile. (constantly ::etag)
                                                               (constantly
                                                                (pr-str config)))}))))]
    (test/is (= :yes-indeed (get-in returned-config [:nomad/current-host :host-private])))
    (test/is (= :of-course (get-in returned-config [:nomad/current-instance :instance-private])))))

(deftest deep-merges-private-config
  (let [config {:nomad/hosts {"my-host"
                              {:database {:username "my-user"
                                          :password "failed..."}
                               :nomad/private-file
                               (DummyPrivateFile.
                                ::etag {:database {:password "password123"}})}}}
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           (with-meta {}
                             {:config-file (DummyConfigFile. (constantly ::etag)
                                                             (constantly
                                                              (pr-str config)))})))]
    (test/is (= "my-user" (get-in returned-config [:nomad/current-host :database :username]))) 
    (test/is (= "password123" (get-in returned-config [:nomad/current-host :database :password])))))

(deftest reloads-private-config-when-private-file-changes
  (let [new-private-file (DummyPrivateFile.
                          "new-etag" {:host-private :yes-indeed})
        config {:nomad/hosts {"my-host"
                              {:nomad/private-file new-private-file}}}
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           (with-meta {:nomad/private
                                       {:nomad/current-host
                                        (with-meta {:host-private :definitely-not}
                                          {:old-etag "old-etag"
                                           :config-file new-private-file})}}
                             {:config-file (DummyConfigFile. (constantly "public-etag")
                                                             (constantly
                                                              (pr-str config)))
                              :old-etag "public-etag"})))]
    (test/is (= :yes-indeed (get-in returned-config [:nomad/current-host :host-private])))))

(defrecord DummyUnchangingPrivateFile [etag*]
  nomad/ConfigFile
  (etag [_] etag*)
  (slurp* [_] (throw (AssertionError. "Shouldn't reload!"))))

(deftest caches-private-config-when-nothing-changes
  (let [private-file (DummyUnchangingPrivateFile. "same-private-etag")
        config {:nomad/hosts {"my-host" {:nomad/private-file private-file}}}
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           (with-meta {:nomad/private
                                       {:nomad/current-host
                                        (with-meta {:host-private :yes-indeed}
                                          {:old-etag "same-private-etag"
                                           :config-file private-file})}}
                             {:config-file (DummyConfigFile. (constantly "same-public-etag")
                                                             (constantly
                                                              (pr-str config)))
                              :old-etag "same-public-etag"})))]
    (test/is (= :yes-indeed (get-in returned-config [:nomad/current-host :host-private])))))
