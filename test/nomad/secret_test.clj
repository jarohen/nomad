(ns nomad.secret-test
  (:require [nomad.secret :refer :all]
            [clojure.test :refer :all]))

(deftest round-trip
  (let [plain-text "hello world!"
        secret-key (generate-key)]
    (is (= plain-text
           (-> plain-text
               (encrypt secret-key)
               (decrypt secret-key))))))
