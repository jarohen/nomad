(ns nomad.temp-080-beta.merge)

(defn deep-merge
  "Like merge-with, but merges maps recursively, choosing the last
  only when there's a non-map at a particular level."
  [maps]

  (if (every? (some-fn map? nil?) maps)
    (apply merge-with #(deep-merge %&) maps)
    (last maps)))

(comment
  (= (deep-merge [{:a {:b {:c 1
                           :d {:x 1 :y 2}}
                       :e 3}
                   :f 4}

                  {:a {:b {:c 2
                           :d {:z 9}
                           :z 3}
                       :e 100}}])

     {:a {:b {:c 2,
              :d {:x 1,
                  :y 2,
                  :z 9}
              :z 3}
          :e 100}
      :f 4}))
