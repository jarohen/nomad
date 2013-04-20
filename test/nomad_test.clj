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

(defrecord DummyPrivateFile [etag content]
  nomad/ConfigFile
  (etag [_] etag)
  (slurp* [_] (pr-str content)))

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
    (test/is (= :yes-indeed (get-in returned-config [:nomad/private :nomad/current-host :host-private])))
    (test/is (= :of-course (get-in returned-config [:nomad/private :nomad/current-instance :instance-private])))))
