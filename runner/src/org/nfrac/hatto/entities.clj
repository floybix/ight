(ns org.nfrac.hatto.entities
  (:require [cljbox2d.core :refer :all]
            [cljbox2d.joints :refer :all]))

(defrecord Entity [entity-type components joints])

;; TODO POIs are like metadata, should be stored in Body user-data
(defrecord BodyPois [body pois])

(defrecord PointState [point position velocity])

(defn entity-mass
  [entity]
  (->> entity
       :components
       vals
       (map (comp mass :body))
       (reduce +)))

(defn entity-angular-velocity
  [entity]
  (let [total-mass (entity-mass entity)]
    (if (zero? total-mass)
      0.0
      (->> entity
           :components
           vals
           (map :body)
           (reduce (fn [av body]
                     (+ av (* (angular-velocity body)
                              (/ (mass body) total-mass))))
                   0)))))

(defn point-state
  [body poi]
  (->PointState poi
                (position body poi)
                (linear-velocity body poi)))

(defn observe-components
  [entity]
  (reduce-kv (fn [m k {:keys [body pois]}]
               (assoc m k
                      {:angle (angle body)
                       :points (for [poi pois]
                                 (point-state body poi))}))
             {}
             (:components entity)))

(defn sense-joints
  [entity inv-dt]
  (reduce-kv (fn [m k jt]
               (assoc m k
                      {:joint-angle (joint-angle jt)
                       :joint-speed (joint-speed jt)
                       :motor-speed (when (motor-enabled? jt) (motor-speed jt))
                       :motor-torque (motor-torque jt inv-dt)}))
             {}
             (:joints entity)))

(defn perceive-entity
  [entity inv-dt]
  {:components (observe-components entity)
   :joints (sense-joints entity inv-dt)
   :angular-velocity (entity-angular-velocity entity)})

(defn set-joint-motors!
  [entity joint-actions]
  (doseq [[k v] joint-actions]
    (if-let [jt (get-in entity [:joints k])]
      (do
        (enable-motor! jt (boolean v))
        (when v
          (motor-speed! jt v)))
      (println "Joint" k "does not exist."))))
