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
  (slurp* [_] (_content))
  (exists? [_] true))

(deftest simple-config
  (let [config {:config :my-config}
        returned-config (#'nomad/load-config
                         (DummyConfigFile. (constantly nil)
                                           (constantly
                                            (pr-str config))))]
    (test/is (= returned-config {:config :my-config}))))

(deftest host-config
  (let [config {:nomad/hosts {"my-host" {:a 1}
                              "not-my-host" {:a 2}}}
        returned-config (with-hostname "my-host"
                          (#'nomad/load-config
                           (DummyConfigFile. (constantly nil)
                                             (constantly
                                              (pr-str config)))))]
    (test/is (= 1 (get-in returned-config [:nomad/current-host :a])))))

(deftest instance-config
  (let [config {:nomad/hosts {"my-host"
                              {:nomad/instances
                               {"DEV1" {:a 1}
                                "DEV2" {:a 2}}}
                              "not-my-host" {:a 2}}}
        returned-config (with-hostname "my-host"
                          (with-instance "DEV2"
                            (#'nomad/load-config
                             (DummyConfigFile. (constantly nil)
                                               (constantly
                                                (pr-str config))))))]
    (test/is (= 2 (get-in returned-config [:nomad/current-instance :a])))))

(deftest caches-when-not-changed
  (let [config {:a 1 :b 2}
        constant-etag "the-etag"
        dummy-config-file (DummyConfigFile. (constantly constant-etag)
                                            #(throw (AssertionError.
                                                     "Shouldn't reload!")))
        returned-config (#'nomad/get-current-config (ref (with-meta config
                                                           {:etag constant-etag}))
                                                    dummy-config-file)]
    (test/is (= config returned-config))))

(deftest reloads-when-changed
  (let [old-config {:a 1 :b 2}
        new-config (assoc old-config :a 3)
        dummy-config-file (DummyConfigFile. (constantly "new-etag")
                                            (constantly (pr-str new-config)))
        returned-config (#'nomad/get-current-config (ref (with-meta old-config
                                                           {:etag "old-etag"}))
                                                    dummy-config-file)]
    (test/is (= new-config returned-config))))
