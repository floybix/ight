(ns org.nfrac.hatto.games
  (:require [org.nfrac.hatto.entities :as ent :refer [with-pois map->Entity]]
            [org.nfrac.hatto.creatures :as creatures]
            [org.nfrac.cljbox2d.core :refer :all]
            [org.nfrac.cljbox2d.vec2d :refer [v-add v-sub polar-xy v-interp v-scale]]))

;; =============================================================================

(defrecord Game
    [game-type
     game-attributes
     game-version
     world
     entities
     player-keys
     time
     dt-secs
     ;; method implementations
     perceive
     act
     world-step
     check-end])

;; =============================================================================

(defn perceive
  [game player-key]
  (let [inv-dt (/ 1 (:dt-secs game))
        me (get-in game [:entities player-key])
        obs (reduce-kv (fn [m k entity]
                         (assoc m k (ent/perceive-entity entity inv-dt)))
                       {}
                       (:entities game))]
    {:time (:time game)
     :my-key player-key
     :entities obs}))

(defn act-now?
  [{:keys [time dt-act-secs last-act-time]}]
  (>= time (+ dt-act-secs last-act-time)))

(defn act-on-joints
  [game player-key actions]
  (ent/set-joint-motors! (get-in game [:entities player-key])
                         (:joints actions))
  game)

(defn world-step
  [game]
  (-> game
      (update-in [:world] step! (:dt-secs game))
      (update-in [:time] + (:dt-secs game))))

(defn check-time-limit
  [game limit-secs]
  (when (> (:time game) limit-secs)
    {:winner nil}))

(defn check-fallen-down
  [game y-val]
  (let [dead (filter (fn [player-key]
                       (let [player (get-in game [:entities player-key])
                             head (-> player :components :head)
                             [x y] (position head)]
                         ;; head has fallen below death level
                         (< y y-val)))
                     (:player-keys game))]
    (when (seq dead)
      {:winner (first (apply disj (:player-keys game) dead))})))

(defn check-highest
  [game limit-secs]
  (when (> (:time game) limit-secs)
    (let [heights (into {}
                        (map (fn [player-key]
                               (let [player (get-in game [:entities player-key])
                                     head (-> player :components :head)
                                     [x y] (position head)]
                                 [player-key y]))
                             (:player-keys game)))
          highest (apply max-key heights (:player-keys game))]
      {:winner highest
       :heights heights})))

;; =============================================================================

(def empty-game
  (map->Game
   {:entities {}
    :player-keys #{}
    :time 0.0
    :dt-secs (/ 1 30.0)
    :dt-act-secs (/ 1 15.0)
    :last-act-time 0.0
    :perceive perceive
    :act act-on-joints
    :world-step world-step
    :check-end (fn [game] nil)}))

(defn add-players
  "Creates entities for each given player and adds them to the world
   and the game recor. `players` is a map from player keywords to a
   creature type. `starting-pts` is a sequence of [x y] positions."
  [game players starting-pts]
  (let [world (:world game)
        es (map (fn [player-type pt i]
                  (creatures/build player-type world pt (- (inc i))))
                (vals players)
                starting-pts
                (range))]
    (-> game
        (update-in [:entities] into (zipmap (keys players) es))
        (update-in [:player-keys] into (keys players)))))

(defn set-player-vels
  [game player-keys vels]
  (doseq [[k vel] (zipmap player-keys vels)
          body (vals (get-in game [:entities k :components]))]
    (linear-velocity! body vel))
  game)

(defn edge-chain
  [vertices attrs]
  (for [[v0 v1] (partition 2 1 vertices)]
    (merge {:shape (edge v0 v1)} attrs)))

(defn edge-loop
  [vertices attrs]
  (edge-chain (concat vertices (take 1 vertices))
              attrs))

;; =============================================================================

(defmulti build*
  (fn [type players opts]
    type))

(defn build
  "Returns a Game record where all Body objects have their entity key
   in user-data ::entity."
  [type players opts]
  (let [game (build* type players opts)]
    (doseq [[ent-k ent] (:entities game)
            [cmp-k cmp] (:components ent)]
      (vary-user-data cmp #(assoc % ::entity ent-k)))
    game))

(defmethod build* :sandbox
  [type players _]
  (let [world (new-world)
        ground (body! world {:type :static}
                      {:shape (box 15 20 [0 -20])
                       :friction 1.0}
                      {:shape (edge [-15 0] [-15 15])}
                      {:shape (edge [15 0] [15 15])})
        pois [[-15 0] [15 0]]
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components {:ground (with-pois ground pois)}})
        starting-pts (map vector [-10 10 0 -5 5] (repeat 10))]
    (-> (assoc empty-game
          :world world
          :entities {:arena arena}
          :camera {:width 40 :height 20 :x-left -20 :y-bottom -5})
        (add-players players starting-pts))))

(defmethod build* :sumo
  [type players _]
  (let [world (new-world)
        ground (body! world {:type :static}
                      {:shape (box 15 20 [0 -20])
                       :friction 1.0})
        pois [[-15 0] [15 0]]
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components {:ground (with-pois ground pois)}})
        starting-pts (map vector [-10 10 0 -5 5] (repeat 10))]
    (->
     (assoc empty-game
       :world world
       :entities {:arena arena}
       :camera {:width 40 :height 20 :x-left -20 :y-bottom -5}
       :check-end (fn [game]
                    (or (check-time-limit game 60)
                        (check-fallen-down game -2))))
     (add-players players starting-pts))))

(defmethod build* :vortex-maze
  [type players _]
  (let [world (new-world [0 0])
        fence-pois [[-12 -10]
                    [-12 10]
                    [12 10]
                    [12 -10]]
        fence (with-pois
                (apply body! world {:type :static}
                       (edge-loop fence-pois
                                  {:friction 1}))
                fence-pois)
        wall-fx {:shape (box 3 0.2)
                 :friction 1}
        wall-pois [[-3 0] [3 0]]
        walls (for [i (range 4)
                    :let [k (keyword (str "wall-" i))
                          pos (get [[5 -5]
                                    [-5 -5]
                                    [-5 5]
                                    [5 5]] i)]]
                [k (with-pois
                     (body! world {:type :static
                                   :position pos
                                   :angle (* Math/PI (/ i 2))}
                            wall-fx)
                     wall-pois)])
        vortex (with-pois
                 (body! world {:type :static
                               :position [0 0]
                               :user-data {:org.nfrac.cljbox2d.testbed/rgb [255 0 0]}}
                        {:shape (circle 1.0)})
                 [[0 0]])
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components (into {:fence fence
                                   :vortex vortex}
                                  walls)})
        starting-pts [[-8 0] [8 0]]
        starting-vels [[-3 -3] [3 3]]]
    (->
     (assoc empty-game
       :world world
       :entities {:arena arena}
       :camera {:width 32 :height 24 :x-left -16 :y-bottom -12}
       :world-step (fn [game]
                     (let [vort-pos (position vortex)]
                       (doseq [k (:player-keys game)
                               body (vals (get-in game [:entities k :components]))
                               :let [pos (center body)
                                     force (-> (v-sub vort-pos pos)
                                               (v-scale)
                                               (v-scale 0.5))]]
                         (apply-force! body force (loc-center body))))
                     (world-step game))
       :check-end (fn [game]
                    (let [dead (distinct (map (comp ::entity user-data)
                                              (contacting vortex)))]
                      (when (seq dead)
                        {:winner (first (apply disj (:player-keys game) dead))}))))
     (add-players players starting-pts)
     (set-player-vels (keys players) starting-vels))))

(defmethod build* :energy-race
  [type players _]
  (let [world (new-world)
        surface (for [x (range -20 20.01 0.2)]
                  [x (+ (/ (Math/pow x 4)
                           (Math/pow 20 3))
                        (* (Math/cos (* x 4))
                           (/ (* x x) (* 20 20))
                           1.5))])
        pois (for [z [0 1/3 2/3 1]]
               (nth surface (* z (dec (count surface)))))
        ground (apply body! world {:type :static}
                      (edge-chain surface
                                  {:friction 1}))
        plat-fx {:shape (edge [-5 0] [5 0])
                 :friction 1}
        plat-pois [[-5 0] [5 0]]
        plat-right (body! world {:type :static
                                 :position [8 15]}
                          plat-fx)
        plat-left (body! world {:type :static
                                :position [-8 15]}
                         plat-fx)
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components {:ground (with-pois ground pois)
                             :plat-left (with-pois plat-left plat-pois)
                             :plat-right (with-pois plat-right plat-pois)}})
        ;; place food by scanning down from above at regular intervals
        food-pos (for [sign [-1 1]
                       xa (concat [2 4 6]
                                  (range 10.5 20))
                       :let [x (* sign xa)]]
                   (let [[rc] (raycast world [x 20] [x -10] :closest)]
                     (if rc
                       (v-add (:point rc) [0 0.2])
                       (do (println (str "raycast missed: " x))
                           [0 0]))))
        food-fx {:shape (polygon [[0 0.5] [-0.25 0] [0.25 0]])
                 :density 5 :restitution 0 :friction 1}
        food (map->Entity
              {:entity-type :food
               :components (into {}
                                 (map-indexed (fn [i pos]
                                                (let [k (keyword (str "food-" i))]
                                                  [k(with-pois
                                                      (body! world {:position pos}
                                                             food-fx)
                                                      [[0 0]])]))
                                              food-pos))})
        starting-pts (map vector [-10 10 0 -5 5] (repeat 10))]
    (->
     (assoc empty-game
       :world world
       :entities {:arena arena
                  :food food}
       :camera {:width 40 :height 20 :x-left -20 :y-bottom -1}
       :check-end (fn [game]
                    (check-highest game 60)))
     (add-players players starting-pts))))

(defmethod build* :altitude
  [type players _]
  (let [world (new-world)
        surface [[-16 0] [-4 0] [0 -2] [4 0] [16 0]]
        ground (with-pois
                 (apply body! world {:type :static}
                        (edge-chain surface
                                    {:friction 1}))
                 surface)
        ceil-y 16
        ceiling (body! world {:type :static}
                       {:shape (edge [-16 ceil-y]
                                     [16 ceil-y])})
        stal-depth 6
        stal-yend 10
        stal-xoff 1
        stal-pois (for [sign [-1 1]
                        frac (range 0.1 0.9 0.2)]
                    (v-interp [0 (- stal-depth)]
                              [(* sign stal-xoff) 0]
                              frac))
        stalactite (with-pois
                     (apply body! world {:type :static
                                         :position [0 ceil-y]}
                            {:shape (polygon [[stal-xoff 0]
                                              [(- stal-xoff) 0]
                                              [0 (- stal-depth)]])
                             :friction 1}
                            (for [pos stal-pois
                                  :let [sign (if (neg? (first pos)) -1 1)]]
                              {:shape (edge pos (v-add pos [(* sign 0.5) 0.1]))
                               :friction 1}))
                     stal-pois)
        rope-length 10
        swing-pois [[-2 0] [2 0]]
        swing (with-pois
                (body! world {:position [0 (- ceil-y rope-length)]}
                       {:shape (box 2 0.1)
                        :friction 1})
                swing-pois)
        ropes (doall
               (for [[x y] swing-pois]
                 (joint! {:type :rope
                          :body-a ceiling
                          :body-b swing
                          :anchor-a [(* x 2) ceil-y]
                          :anchor-b [x y]
                          :max-length rope-length})))
        block-fx {:shape (box 2 1.5)
                  :density 2
                  :friction 1}
        block-pois [[-2 1.5]
                    [2 1.5]]
        blocks (for [[sign s] [[-1 "left"] [1 "right"]]]
                 [(keyword (str "block-" s))
                  (with-pois
                    (body! world {:position [(* sign 13) 1.5]}
                           block-fx)
                    block-pois)])
        mini-fx {:shape (box 0.5 0.5)
                 :density 2
                 :friction 1}
        mini-pois [[-0.5 0.5]
                   [0.5 0.5]
                   [0.5 -0.5]
                   [-0.5 -0.5]]
        minis (for [[sign s] [[-1 "left"] [1 "right"]]]
                [(keyword (str "mini-" s))
                 (with-pois
                   (body! world {:position [(* sign 4.5) 0.5]}
                          mini-fx)
                   mini-pois)])
        plat-fx {:shape (edge [-2 0] [2 0])
                 :friction 1}
        plat-pois [[-2 0] [2 0]]
        plats (for [[sign s] [[-1 "left"] [1 "right"]]]
                [(keyword (str "plat-" s))
                 (with-pois
                   (body! world {:type :static
                                 :position [(* sign 8) 6]}
                          plat-fx)
                   plat-pois)])
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components (into {:ground ground
                                   :stalactite stalactite
                                   :swing swing}
                                  (concat blocks minis plats))})
        starting-pts (map vector [-10 10] (repeat 2.5))]
    (->
     (assoc empty-game
       :world world
       :entities {:arena arena
                  }
       :camera {:width 40 :height 20 :x-left -20 :y-bottom -3}
       :check-end (fn [game]
                    (check-highest game 60)))
     (add-players players starting-pts))))

(defmethod build* :hunt
  [type players _]
  (let [world (new-world)
        fence-pois [[-12 -6]
                    [-12 6]
                    [12 6]
                    [12 -6]]
        fence (with-pois
                (apply body! world {:type :static
                                    :position [0 6]}
                       (edge-loop fence-pois
                                  {:friction 1}))
                fence-pois)
        ;; -4m to left, 4.1m to right
        pedestal-pts [[-4.0 0.0] [-2.5 0.2] [-2.0 0.5] [-2.1 1.1] [-1.9 1.8] [-1.5 2.0]
                      [4.0 2.3] [4.1 2.1] [3.5 1.7] [3.0 1.0] [3.1 0.7] [3.25 0.0]]
        pedestal (with-pois
                   (apply body! world {:type :static
                                       :position [0 0]}
                          (edge-chain pedestal-pts
                                      {:friction 1}))
                   pedestal-pts)
        ;; 3m to left, 3m to right
        r-ramp-pts (into [[-3.0 0.0] [-1.0 1.5] [0.0 2.0]]
                         ;; steps
                         (reductions v-add
                                 [0.5 2.0]
                                 (interleave (repeat 5 [0.0 0.5])
                                             (repeat 5 [0.5 0.0]))))
        r-ramp (with-pois
                 (apply body! world {:type :static
                                     :position [(- 12 3) 0]}
                        (edge-chain r-ramp-pts
                                    {:friction 1}))
                 r-ramp-pts)
        ;; 1m to left, 1.5m to right
        l-ramp-pts [[-1.0 1.2] [0.5 0.3] [1.5 0.0]]
        l-ramp (with-pois
                 (apply body! world {:type :static
                                     :position [(+ -12 1) 0]}
                        (edge-chain l-ramp-pts
                                    {:friction 1}))
                 l-ramp-pts)
        arena (map->Entity
               {:entity-type :arena
                :arena-type type
                :components (into {:fence fence
                                   :pedestal pedestal
                                   :r-ramp r-ramp
                                   :l-ramp l-ramp}
                                  ())})
        starting-pts [[-10 8] [10 8]]]
    (->
     (assoc empty-game
       :world world
       :entities {:arena arena
                  }
       :camera {:width 30 :height 15 :x-left -15 :y-bottom -1}
       :check-end (fn [game]
                    nil))
     (add-players players starting-pts))))
