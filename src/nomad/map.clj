(ns nomad.map)

(defn deep-merge
  "Like merge-with, but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level.
 
  (deep-merge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
               {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [& maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (first maps)))
    maps))
