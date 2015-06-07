(ns nomad-test
  (:require [nomad :refer [defconfig read-config with-location-override]]
            [clojure.test :as test :refer [deftest]]
            [clojure.java.io :as io]))

(comment
  ;; This bit is for some manual integration testing

  (defconfig test-config (io/resource "test-config.edn"))

  (time (with-location-override {:host "my-host"
                                 :instance  "test-instance"}
          (test-config)))

  (with-location-override {:host "my-host"
                           :instance "env-var-test"}
    (test-config)))
