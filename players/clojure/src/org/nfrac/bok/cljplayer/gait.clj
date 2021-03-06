(ns org.nfrac.bok.cljplayer.gait
  (:require [org.nfrac.bok.cljplayer.util :as util
             :refer [abs x-val turn-towards HALF_PI]]))

(defn x-offset
  [component]
  (x-val (:position (first (:points component)))))

(defn grounded?
  [cmp]
  (seq (:contacts cmp)))

(defn balance-d
  ;; balance feedback to swing hip - distance term
  [me swing-leg-k]
  (let [com (:center-of-mass me)
        cmp (get-in me [:components swing-leg-k])]
    (- (x-val com) (x-offset cmp))))

(defn eval-gait-phase
  ""
  [me dir pose time-in-phase]
  (let [components (:components me)
        my-joints (:joints me)
        done? (or
               (when-let [cs (:contact (:until pose))]
                 (some grounded? (map components cs)))
               (when-let [dt (:dt (:until pose))]
                 (>= time-in-phase dt)))]
    (when-not done?
      (->>
       (for [[jt-k params] (:joints pose)
             :when (seq params)
             :let [ang-vel (get-in my-joints [jt-k :joint-speed])
                   cmp-k (or (:on-parent params) jt-k)
                   curr-ang (cond
                             (:joint-angle params)
                             (get-in my-joints [jt-k :joint-angle])
                             (:angle params)
                             (get-in components [cmp-k :angle]))
                   adj (if-let [[balance-k c-d c-v] (:balance params)]
                         (+ (* c-d (balance-d me balance-k))
                            (* c-v (x-val (:velocity me))))
                         0.0)
                   target-ang (-> (or (:joint-angle params)
                                      (:angle params))
                                  (* dir)
                                  (+ adj))
                   ang-diff (if (:on-parent params)
                              (- curr-ang target-ang)
                              (- target-ang curr-ang))]]
         [jt-k
          (turn-towards ang-diff 0.0
                        ang-vel (:speed params)
                        (:k-p params 100) (:k-d params 10))])
       (into {})
       (hash-map :joint-motors)))))

(defn pose-with-defaults
  [pose limb-defaults defaults]
  (update-in pose [:joints]
             (fn [joints]
               (reduce-kv (fn [m k v]
                           (assoc m k
                                  (merge defaults
                                         (get limb-defaults k)
                                         v)))
                         {}
                         joints))))

(defn eval-gait
  "Applies a given gait specification in the current situation and in
   the given direction (-1 left, 1 right). Returns `[actions
   new-gait-state]`."
  [me dir gait gait-state t]
  (loop [phase (:phase gait-state 0)
         time-in-phase (- t (:start gait-state 0))]
    (let [pose (-> (get (:poses gait) phase)
                   (pose-with-defaults (:limb-defaults gait)
                                       (:defaults gait)))]
      (if-let [actions (eval-gait-phase me dir pose time-in-phase)]
        [actions
         {:phase phase, :start (- t time-in-phase)}]
        (recur (mod (inc phase) (count (:poses gait)))
               0.0)))))

(defn symmetric-gait
  [half-gait symm-map]
  (let [opp (fn [k] (or (symm-map k) k))
        opp-val (fn [m]
                  (cond-> m
                          (:balance m)
                          (update-in [:balance 0] opp)
                          (:on-parent m)
                          (update-in [:on-parent] opp)))
        opp-pose (fn [pose]
                   (-> (update-in pose [:joints]
                                  (fn [joints]
                                    (zipmap (map opp (keys joints))
                                            (map opp-val (vals joints)))))
                       (update-in [:until :contact]
                                  #(set (map opp %)))))]
    (update-in half-gait [:poses]
               (fn [poses]
                 (into poses (map opp-pose poses))))))

(def humanoid-symmetry-map
  (let [limbs {:legs [[:leg-a1 :leg-a2 :leg-a3]
                      [:leg-b1 :leg-b2 :leg-b3]]
               :arms [[:arm-a1 :arm-a2]
                      [:arm-b1 :arm-b2]]}]
    (->> (vals limbs)
         (mapcat (fn [[limb-a limb-b]]
                   [(zipmap limb-a limb-b)
                    (zipmap limb-b limb-a)]))
         (reduce merge))))

(def humanoid-leg-cmp-ks [:leg-a1 :leg-a2 :leg-a3
                          :leg-b1 :leg-b2 :leg-b3])

(def humanoid-arm-cmp-ks [:arm-a1 :arm-a2
                          :arm-b1 :arm-b2])

;; Adapted from Yin et al (2007) SIMBICON.
(def humanoid-half-walk
  {:defaults {:speed 8
              :k-p 100
              :k-d 10}
   :limb-defaults (zipmap humanoid-arm-cmp-ks
                          (repeat {:speed 2
                                   :k-p 40
                                   :k-d 4}))
   :poses [{:until {:dt 0.3}
            :joints
            { ;; stance leg
             :leg-a1 {:on-parent :torso, :angle -0.05}
             :leg-a2 {:joint-angle -0.05}
             :leg-a3 {:joint-angle (+ HALF_PI 0.0)}
             ;; swing leg
             :leg-b1 {:angle 0.5, :balance [:leg-b2 0.0 0.2]}
             :leg-b2 {:joint-angle -1.1}
             :leg-b3 {:joint-angle (+ HALF_PI 0.2)}
             ;; arms
             :arm-a1 {:joint-angle 0.3}
             :arm-a2 {:joint-angle 0.4}
             :arm-b1 {:joint-angle -0.3}
             :arm-b2 {:joint-angle 0.4}}
            }
           {:until {:contact #{:leg-b2 :leg-b3}}
            :joints
            { ;; stance leg
             :leg-a1 {:on-parent :torso, :angle -0.05}
             :leg-a2 {:joint-angle -0.1}
             :leg-a3 {:joint-angle (+ HALF_PI 0.2)}
             ;; swing leg
             :leg-b1 {:angle -0.05, :balance [:leg-b2 2.0 0.0]}
             :leg-b2 {:joint-angle -0.1}
             :leg-b3 {:joint-angle (+ HALF_PI 0.0)}
             ;; arms
             :arm-a1 {:joint-angle -0.3}
             :arm-a2 {:joint-angle 0.4}
             :arm-b1 {:joint-angle 0.3}
             :arm-b2 {:joint-angle 0.4}}
            }
           ]})

(def humanoid-walk
  (symmetric-gait humanoid-half-walk humanoid-symmetry-map))
