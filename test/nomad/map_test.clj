(ns nomad.map-test
  (:require [nomad.map :refer :all]
            [clojure.test :refer :all]))

(deftest deep-merge-test
  (let [v1 {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
        v2 {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}}]
    (is (= {:a {:b {:c 2, :d {:x 1, :y 2, :z 9}, :z 3}, :e 100}, :f 4}
           (deep-merge v1 v2)))))
