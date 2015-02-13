(ns org.nfrac.bok.tests.four
  (:require [org.nfrac.bok.games :as games]
            [org.nfrac.bok.visual-runner :as visrun]
            [org.nfrac.bok.runner :as runner]
            [org.nfrac.bok.entities :as ent]
            [clojure.pprint :refer [pprint]]))

(defn action
  [info]
  {})

(defn -main
  "Run the test sketch."
  [& [arena-type]]
  (let [arena-type (or (keyword arena-type) :sumo)
        game (games/build arena-type
                          {:player-a :bipoid
                           :player-b :humanoid
                           :player-c :wormoid
                           :player-d :bipoid}
                          {})]
    (println "bipoid mass:"
             (-> game :entities :player-a ent/entity-mass))
    (println "humanoid mass:"
             (-> game :entities :player-b ent/entity-mass))
    (println "wormoid mass:"
             (-> game :entities :player-c ent/entity-mass))
    (-> game
        (visrun/run-with-display #(runner/step-local % {:player-a action
                                                        :player-b action
                                                        :player-c action
                                                        :player-d action}))
        :final-result
        pprint)))