(ns nomad.map)

(defn deep-merge
  "Like merge, but merges maps recursively.

  (deep-merge {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
              {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  -> {:a {:b {:c 2, :d {:x 1, :y 2, :z 9}, :z 3}, :e 100}, :f 4}"
  [& maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (last maps)))
    maps))
