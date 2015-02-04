(ns org.nfrac.bok.creatures
  (:require [org.nfrac.bok.entities :refer [set-pois entity]]
            [org.nfrac.cljbox2d.core :refer :all]
            [org.nfrac.cljbox2d.vec2d :refer [v-add]]))

(defmulti build
  (fn [type world _ _]
    type))

(defn revo-joint!
  [body-a body-b world-anchor & {:as more}]
  (joint! (into {:type :revolute
                 :body-a body-a
                 :body-b body-b
                 :world-anchor world-anchor}
                more)))

(defn limb
  "Builds a limb of consecutive segments based on the `lengths` sequence.
   Each segment is attached with a revolute joint, and the first is
   likewise attached to `host` body. Returns keys :components
   and :joints, each a map with keywords made from `prefix` and a
   number."
  [world host position fixture-spec
   & {:keys [lengths width prefix]
      :or {lengths [0.75 0.75], width 0.1, prefix "seg-"}}]
  (let [segs (loop [i 0
                    pos position
                    segs []]
               (if (>= i (count lengths))
                 segs
                 (let [len (nth lengths i)
                       seg (-> (body! world {:position pos}
                                      (assoc fixture-spec
                                        :shape (rod [0 0] 0 len width)))
                               (set-pois [[len 0.0]]))
                       prev (or (:component (peek segs))
                                host)
                       rj (revo-joint! prev seg pos)]
                   (recur (inc i)
                          (v-add pos [(- len (/ width 2)) 0.0])
                          (conj segs {:key (keyword (str prefix (inc i)))
                                      :component seg
                                      :joint rj})))))]
    {:components (into {} (map (juxt :key :component) segs))
     :joints (into {} (map (juxt :key :joint) segs))}))

(defmethod build :legsoid
  [type world position group-index]
  (let [head (-> (body! world {:position position
                               :fixed-rotation true}
                        {:shape (circle 0.5)
                         :density 5
                         :friction 1.0
                         :group-index group-index})
                 (set-pois [[0 0]]))
        limb-spec {:density 10
                   :friction 1.0
                   :group-index group-index}
        limbs (merge-with
               merge ;; merge nested maps
               (limb world head position limb-spec
                     :lengths [1.0 1.0] :prefix "leg-a")
               (limb world head position limb-spec
                     :lengths [1.0 1.0] :prefix "leg-b"))]
    (entity (assoc (:components limbs)
              :head head)
            :joints (:joints limbs)
            :entity-type :creature
            :creature-type type
            :group-index group-index)))

(defmethod build :humanoid
  [type world position group-index]
  (let [head-pos (v-add position [0 0.75])
        head (-> (body! world {:position head-pos}
                        {:shape (circle 0.25)
                         :density 5
                         :friction 1.0
                         :group-index group-index})
                 (set-pois [[0 0]]))
        torso-pos position
        torso-pts [[0.00 0.50]
                   [-0.25 0.25]
                   [-0.25 -0.25]
                   [0.00 -0.50]
                   [0.25 -0.25]
                   [0.25 0.25]]
        torso-pois [[-0.25 0.0]
                    [0.25 0.0]]
        torso (-> (body! world {:position torso-pos}
                         {:shape (polygon torso-pts)
                          :density 5
                          :friction 1.0
                          :group-index group-index})
                  (set-pois torso-pois))
        wj (joint! {:type :weld :body-a head :body-b torso
                    :world-anchor head-pos})
        limb-spec {:density 20
                   :friction 1.0
                   :group-index group-index}
        arm-pos (v-add torso-pos [0.0 0.40])
        leg-pos (v-add torso-pos [0.0 -0.40])
        limbs (merge-with
               merge ;; merge nested maps
               (limb world torso arm-pos (assoc limb-spec :density 5)
                     :lengths [0.75 0.75] :prefix "arm-a")
               (limb world torso arm-pos (assoc limb-spec :density 5)
                     :lengths [0.75 0.75] :prefix "arm-b")
               (limb world torso leg-pos limb-spec
                     :lengths [0.75 0.75 0.25] :prefix "leg-a")
               (limb world torso leg-pos limb-spec
                     :lengths [0.75 0.75 0.25] :prefix "leg-b"))]
    (entity (assoc (:components limbs)
              :head head
              :torso torso)
            :joints (:joints limbs)
            :entity-type :creature
            :creature-type type
            :group-index group-index)))

(defmethod build :wormoid
  [type world position group-index]
  (let [head-pos (v-add position [-2.5 0])
        head (-> (body! world {:position head-pos}
                        {:shape (circle 0.25)
                         :density 5
                         :friction 1.0
                         :group-index group-index})
                 (set-pois [[0 0]]))
        limb-spec {:density 10
                   :friction 1.0
                   :group-index group-index}
        segs (limb world head head-pos limb-spec
                   :lengths (repeat 5 1.0) :prefix "seg-")]
    (entity (assoc (:components segs)
              :head head)
            :joints (:joints segs)
            :entity-type :creature
            :creature-type type
            :group-index group-index)))