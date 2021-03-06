(ns isle.core
  (:require-macros [isle.macros :refer [spy]])
  (:require [clojure.string :as string]
            [rand-cljc.core :as rng]
            [vdom.core :refer [renderer]]
            [isle.math :as m]
            [isle.svg :as s]))

(enable-console-print!)

(defn bounds [pts]
  (let [extents (juxt #(apply min %) #(apply max %))
        [x1 x2] (extents (map first pts))
        [y1 y2] (extents (map second pts))]
    [x1 y1 (- x2 x1) (- y2 y1)]))

(defn zoom-to [[x y w h] [x' y' w' h']]
  (let [s (min (/ w' w) (/ h' h))]
    (str (s/translate (+ x' (/ w' 2)) (+ y' (/ h' 2)))
         "scale(" (* s 0.95) ")"
         (s/translate (- (+ x (/ w 2))) (- (+ y (/ h 2)))))))

(defn ui [emit {:keys [island] :as model}]
  (let [size 600]
    [:main {}
     [:div {}
      [:div {}
       [:button
        {:className "unprinted"
         :onclick #(emit :reset-points)}
        "New Island"]]
      [:div {}
       [:svg {:width size :height size}
        [:rect {:class "water" :width size :height size}]
        (let [pts (map :position island)]
        [:path {:class "island"
                :transform (zoom-to (bounds pts) [0 0 size size])
                :d (s/closed-path pts)}])]]]]))

(defn loopback [xs]
  (concat xs [(first xs)]))

(defn seed-point [rng]
  {:offset (rng/rand rng)
   :balance (rng/rand rng)
   :max-offset (+ 0.05 (rng/rand rng))})

(defn circle [rng n radius]
  (for [theta (range 0 m/tau (/ m/tau n))
        :let [{:keys [offset balance] :as seed} (seed-point rng)]]
    (merge seed
           {:id (gensym "radial")
            :position (let [r (* radius (+ 1 (- offset balance)))]
                        [(* r (m/cos theta))
                         (* r (m/sin theta))])
            :source {:type :radial
                     :center [0 0]
                     :angle theta
                     :radius radius}})))

(defn midpoint [rng a b]
  (let [offset (rng/rand rng)
        max-offset (m/avg (map :max-offset [a b]))
        balance (m/avg (map :balance [a b]))]
    {:id (gensym "midpoint")
     :offset offset
     :balance balance
     :max-offset max-offset
     :position (let [[x y :as a] (:position a)
                     [x' y' :as b] (:position b)
                     len (m/dist a b)
                     vlen (* max-offset len (- offset balance))
                     [mx my] [(m/avg [x x']) (m/avg [y y'])]
                     [dx dy] (m/unit-vector [(- (- y y')) (- x x')])]
                 [(+ mx (* vlen dx))
                  (+ my (* vlen dy))])
     :source {:type :midpoint
              :left (:id a)
              :right (:id b)}}))

(defn subdivide-with [f pred coll max-depth]
  (if (pos? max-depth)
    (->> coll
      (partition 2 1)
      (mapcat (fn [[left right]]
                (if (pred left right)
                  (subdivide-with f pred [left (f left right) right] (dec max-depth))
                  [left]))))
    coll))

(defn island [rng max-depth]
  (as-> (rng/rand-int rng 17) x
    (+ 3 x)
    (circle rng x 100)
    (loopback x)
    (subdivide-with
      (partial midpoint rng)
      #(<= 2 (m/dist (:position %1) (:position %2)))
      x
      max-depth)))

(defn generate-model []
  (let [seed (let [s (string/replace js/location.hash #"#" "")]
               (when-not (string/blank? s)
                 (js/parseInt s)))
        rng (rng/rng (or seed (rand-int 100000000)))]
    {:rng rng
     :island (island rng 15)}))

(def model (atom (generate-model)))

(defmulti emit (fn [t & _] t))

(defmethod emit :reset-points [_]
  (let [seed (rand-int 100000000)
        rng (rng/rng seed)]
    (set! (.-hash js/location) seed)
    (swap! model
      (fn [m]
        (assoc m
          :rng rng
          :island (island rng 15))))))

(defonce render!
  (let [r (renderer (.getElementById js/document "app"))]
    #(r (ui emit @model))))

(defonce on-update
  (add-watch model :rerender
    (fn [_ _ _ model]
      (render! model))))

(defonce hash-change
  (.addEventListener js/window "hashchange"
    #(reset! model (generate-model))))

(render! @model)
