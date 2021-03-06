(ns org.nfrac.bok.games
  (:require [org.nfrac.bok.entities :as ent]
            [org.nfrac.bok.creatures :as creatures]
            [org.nfrac.cljbox2d.core
             :refer [step! user-data vary-user-data position center body-of
                     body! destroy! box apply-impulse!
                     contacting all-current-contacts
                     set-buffering-contact-listener!
                     linear-velocity! gravity gravity-scale!
                     apply-force! loc-center]]
            [org.nfrac.cljbox2d.vec2d
             :refer [v-add v-sub polar-xy v-scale]]))

;; =============================================================================

(defrecord Game
    [game-id
     game-version
     game-type
     world
     contact-buffer
     entities
     player-keys
     dead-players
     time
     dt-secs
     game-over-secs
     player-raycast
     player-gun
     player-energy
     ;; method implementations
     perceive
     act
     game-step
     check-end])

;; =============================================================================
;; ## default method implementations

(defn contacts-by-body
  [contacts]
  (->> contacts
       (reduce (fn [m cd]
                 (let [body-a (body-of (:fixture-a cd))
                       body-b (body-of (:fixture-b cd))
                       m1 (assoc! m body-a (conj (get m body-a []) cd))
                       m2 (assoc! m1 body-b (conj (get m1 body-b []) cd))]
                   m2))
               (transient {}))
       (persistent!)))

(defn perceive
  [game player-key]
  (let [inv-dt (/ 1 (:dt-secs game))
        me (get-in game [:entities player-key])
        other-players (disj (:player-keys game) player-key)
        body->contacts (contacts-by-body (deref (:contact-buffer game)))
        obs (->> (:entities game)
                 (map (fn [[k ent]]
                        ;; filter out perception of other players if out of sight
                        (if (and (other-players k)
                                 (not (ent/line-of-sight? (:world game) me
                                                          player-key ent k)))
                          nil
                          ;; otherwise - normal perception
                          [k (ent/perceive-entity ent inv-dt (= k player-key)
                                                  body->contacts)])))
                 (into {}))]
    {:game-type (:game-type game)
     :gravity (:gravity game)
     :time (:time game)
     :my-key player-key
     :other-players other-players
     :dead-players (:dead-players game)
     :guns (:player-gun game)
     :energy (:player-energy game)
     :raycast (get-in game [:player-raycast player-key])
     :entities obs}))

(defn act
  [game player-key actions]
  (let [me (get-in game [:entities player-key])]
    (ent/apply-joint-actions! me
                              (:joint-motors actions)
                              (:joint-torques actions))
    (assoc-in game [:player-raycast player-key]
              (ent/raycast-perception (:world game) me player-key
                                      (:raycast actions)))))

(defn world-step
  [game]
  (swap! (:contact-buffer game) empty)
  (-> game
      (update-in [:world] step! (:dt-secs game))
      (update-in [:time] + (:dt-secs game))))

(def Inf Double/POSITIVE_INFINITY)

(defn check-dead
  [game]
  (let [{:keys [dead-players player-keys]} game]
    (if (and (seq dead-players)
             (>= (count dead-players)
                 (dec (count player-keys))))
      {:winner (first (remove dead-players player-keys))})))

(defn check-dead-or-time-limit
  [game]
  (if (>= (:time game) (or (:game-over-secs game) Inf))
    {:winner nil}
    (check-dead game)))

;; =============================================================================

(defn empty-game
  [world]
  (map->Game
   {:entities {}
    :player-keys #{}
    :dead-players #{}
    :time 0.0
    :dt-secs (/ 1 32.0)
    :game-over-secs 60.0
    :world world
    ;; need this for perception of contact impulses (pressure sensor)
    :contact-buffer (set-buffering-contact-listener! world)
    ;; default method implementations
    :perceive perceive
    :act act
    :game-step world-step
    :check-end check-dead-or-time-limit}))

(defn add-players
  "Creates entities for each given player and adds them to the world
   and the game record. `players` is a map from player keywords to a
   creature type. `starting-pts` is a sequence of [x y] ground
   positions to be assigned to players by order of key."
  [game players starting-pts]
  (let [world (:world game)
        es (map (fn [[_ player-type] pt i]
                  (creatures/build player-type world pt (- (inc i))))
                (sort players)
                starting-pts
                (range))]
    (-> game
        (update-in [:entities] into (zipmap (keys players) es))
        (update-in [:player-keys] into (keys players)))))

(defn set-player-vels
  "For zero-gravity games."
  [game player-keys vels]
  (doseq [[k vel] (zipmap player-keys vels)
          body (vals (get-in game [:entities k :components]))]
    (linear-velocity! body vel))
  game)

;; =============================================================================

(defmulti build*
  (fn [game-id players opts]
    game-id))

(defn known-game-ids
  []
  (set (keys (methods build*))))

(defn build
  "Returns a Game record for the named `game-id`, assuming a
   corresponding multimethod of `build*` has been registered. Argument
   `players` should be a map of player id keywords to their creature
   type (see `creatures/build`). The `opts` map is passed to the game
   build method and also merged into the game record.

   This wrapper function sets user-data on all Bodies with their
   entity and components keys in `:org.nfrac.bok/entity` and
   `:org.nfrac.bok/component`. Also sets :game-id and :gravity on the
   game record."
  [game-id players opts]
  (let [game (build* game-id players opts)]
    (doseq [[ent-k ent] (:entities game)
            [cmp-k cmp] (:components ent)]
      (vary-user-data cmp assoc
                      :org.nfrac.bok/entity ent-k
                      :org.nfrac.bok/component cmp-k))
    (->
     (assoc game
       :game-id game-id
       :gravity (gravity (:world game)))
     ;; store options (e.g. override the game timeout)
     (merge opts))))

;; =============================================================================
;; ## game-type :sumo
;;
;; Players die if they fall below the death level. Last player
;; remaining is the winner.

(defn eliminate-fallen
  [game death-level]
  (let [dead (filter (fn [player-key]
                       (let [player (get-in game [:entities player-key])
                             head (-> player :components :head)
                             [x y] (position head)]
                         ;; head has fallen below death level
                         (< y death-level)))
                     (:player-keys game))]
    (if (seq dead)
      (update-in game [:dead-players] into dead)
      game)))

(defn sumo-game
  [world entities players starting-pts death-level]
  (->
   (empty-game world)
   (assoc
       :game-type :sumo
       :entities entities
       :game-step (fn [game]
                    (-> (world-step game)
                        (eliminate-fallen death-level))))
   (add-players players starting-pts)))

;; =============================================================================
;; ## game-type :altitude
;;
;; Winner is the player with highest (head) position when the time
;; limit is reached.

(defn check-highest
  [game]
  (when (>= (:time game) (or (:game-over-secs game) Inf))
    (let [heights (->>
                   (map (fn [player-key]
                          (let [player (get-in game [:entities player-key])
                                head (-> player :components :head)
                                [x y] (position head)]
                            [player-key y]))
                        (:player-keys game))
                   (into {}))
          highest (apply max-key heights (:player-keys game))]
      {:winner highest
       :heights heights})))

(defn altitude-game
  [world entities players starting-pts]
  (->
   (empty-game world)
   (assoc
       :game-type :altitude
       :entities entities
       :game-step (fn [game]
                    (-> (world-step game)
                        (eliminate-fallen -20)))
       :check-end (fn [game]
                    (or (check-dead game)
                        (check-highest game))))
   (add-players players starting-pts)))

;; =============================================================================
;; ## game-type :energy-race
;;
;; Winner is the player with most energy when the time limit is
;; reached. Players can touch food particles with head to eat them and
;; gain energy. Moving joints uses energy.

(defn check-max-energy
  [game]
  (when (>= (:time game) (or (:game-over-secs game) Inf))
    (let [[winner energy] (apply max-key val (:player-energy game))]
      {:winner winner
       :energy (:player-energy game)})))

(defn dislodge-touched-food
  "If any food particles are touched, dislodge them - switch on gravity."
  [game]
  (doseq [cd (all-current-contacts (:world game))
          fixt [(:fixture-a cd) (:fixture-b cd)]
          :let [body (body-of fixt)]]
    (when (:food-joules (user-data body))
      (gravity-scale! body 1.0)))
  game)

(defn energy-accounting
  [game]
  (let [world (:world game)]
    (reduce
     (fn [game player-key]
       (let [me (get-in game [:entities player-key])
             e-loss (ent/entity-work-joules me (:dt-secs game))
             head (get-in me [:components :head])
             snack (first (filter (comp :food-joules user-data)
                                  (contacting head)))
             snack-k (when snack (:org.nfrac.bok/entity (user-data snack)))
             e-gain (if snack (:food-joules (user-data snack)) 0)
             energy (+ (get-in game [:player-energy player-key])
                       (- e-gain e-loss))]
         (when snack
           (destroy! snack))
         (-> (if snack
               (update-in game [:entities] dissoc snack-k)
               game)
             (update-in [:player-energy] assoc player-key energy))))
     game
     (:player-keys game))))

(defn energy-race-game
  [world entities players starting-pts]
  (->
   (empty-game world)
   (assoc
       :game-type :energy-race
       :entities entities
       :player-energy (zipmap (keys players) (repeat 300.0))
       :game-step (fn [game]
                    (-> game
                        ;; destroying bodies, if we do it before perception
                        ;; then the data will refer to those missing bodies.
                        (dislodge-touched-food)
                        (energy-accounting)
                        (world-step)))
       :check-end check-max-energy)
   (add-players players starting-pts)))


;; =============================================================================
;; ## game-type :hunt
;;
;; Winner is last surviving player. Players are killed if hit by
;; bullets. Each player has a gun attached to their head. It does not
;; collide with things, but it has some angle at which it is pointed.
;; There are special actions to rotate the gun and to fire the gun.
;; Guns can be rotated at a maximum of 1.5 radians per second, so
;; aiming can take several time steps (~ 2 seconds to flip). After
;; firing (and at the start of the game), a gun can not be fired again
;; for 2.0 seconds. Currently there is effectively no limit to ammo.

(def GUN_SPEED 1.5)
(def GUN_AMMO 50)
(def GUN_RELOAD 2) ;; seconds

(defn gun-act
  [game player-key actions]
  (let [world (:world game)
        gun-info (get-in game [:player-gun player-key])
        ang (:angle gun-info)]
    (if (and (:fire (:gun actions))
             (zero? (:reload-countdown gun-info))
             (pos? (:ammo gun-info)))
      ;; fire gun
      (let [head (get-in game [:entities player-key :components :head])
            group-index (get-in game [:entities player-key :group-index])
            bullet (body! world {:position (v-add (position head)
                                                  (polar-xy 0.5 ang))
                                 :angle ang
                                 :bullet true}
                          {:shape (box 0.2 0.05)
                           :density 20
                           :group-index group-index
                           :user-data {::bullet-of player-key}})
            impulse (polar-xy 25 ang)]
        (apply-impulse! bullet impulse (center bullet))
        (apply-impulse! head (v-scale impulse -1) (center head))
        (-> game
            (update-in [:player-gun player-key :ammo] dec)
            (assoc-in [:player-gun player-key :reload-countdown]
                      GUN_RELOAD)))
      ;; otherwise, not firing gun
      (let [dt (:dt-secs game)
            da (-> (:speed (:gun actions) 0)
                   (min GUN_SPEED)
                   (max (- GUN_SPEED))
                   (* dt))]
        (-> game
            (update-in [:player-gun player-key :angle] + da)
            (update-in [:player-gun player-key :reload-countdown]
                       #(max 0 (- % dt))))))))

(defn eliminate-shot
  [game]
  (let [contacts (deref (:contact-buffer game))
        hits (keep (fn [{:keys [fixture-a fixture-b]}]
                     (cond
                      (::bullet-of (user-data fixture-a))
                      [fixture-a fixture-b]
                      (::bullet-of (user-data fixture-b))
                      [fixture-b fixture-a]))
                   contacts)
        dead (distinct
              (keep (fn [[_ target-fixt]]
                      (->> (body-of target-fixt)
                           (user-data)
                           :org.nfrac.bok/entity
                           (get (:player-keys game))))
                    hits))]
    (doseq [[bullet-fixt _] hits]
      (destroy! (body-of bullet-fixt)))
    (if (seq dead)
      (update-in game [:dead-players] into dead)
      game)))

(defn hunt-game
  [world entities players starting-pts]
  (->
   (empty-game world)
   (assoc
       :game-type :hunt
       :entities entities
       :player-gun (zipmap (keys players)
                           (repeat {:angle 0.0
                                    :ammo GUN_AMMO
                                    :reload-countdown GUN_RELOAD}))
       :act (fn [game player-key actions]
              (-> game
                  (act player-key actions)
                  (gun-act player-key actions)))
       :game-step (fn [game]
                    (-> (world-step game)
                        (eliminate-shot))))
   (add-players players starting-pts)))

;; =============================================================================
;; ## game-type :vortex
;;
;; Winner is last remaining player. Players are killed if they touch
;; the vortex body. There is a small suction force towards the vortex.

(defn apply-suction
  "Apply suction force towards vortex."
  [game vortex-body]
  (let [vort-pos (position vortex-body)]
    (doseq [k (:player-keys game)
            body (vals (get-in game [:entities k :components]))
            :let [pos (center body)
                  force (-> (v-sub vort-pos pos)
                            (v-scale)
                            (v-scale 0.5))]]
      (apply-force! body force (loc-center body)))
    game))

(defn eliminate-vortexed
  "Kill players touching vortex."
  [game vortex-body]
  (let [player-keys (:player-keys game)
        dead (distinct
              (map (comp player-keys :org.nfrac.bok/entity user-data)
                   (contacting vortex-body)))]
    (if (seq dead)
      (update-in game [:dead-players] into dead)
      game)))

(defn vortex-game
  [world entities players starting-pts vortex-body]
  (vary-user-data vortex-body assoc :vortex? true)
  (->
   (empty-game world)
   (assoc
       :game-type :vortex
       :entities entities
       :game-step (fn [game]
                    (-> (world-step game)
                        (apply-suction vortex-body)
                        (eliminate-vortexed vortex-body))))
   (add-players players starting-pts)))
