(ns ubik.handlers.page
  (:require [garden.core :refer [css]]
            [hiccup
             [core :as hiccup]
             [page :refer [include-css]]]))

(def controller-style
  (css
   [:body {:background-color "#323232"}]
   [:#main-container {:position "absolute" :top 0 :right 0 :left 0 :bottom 0 :visibility "hidden"}]
   [:#wait-container {:position "absolute" :top 0 :right 0 :left 0 :bottom 0 :visibility "visible"}]
   [:#wait-inner-container {:position "fixed" :bottom 0 :width "100%" :height "33%"}]
   [:.swiper-container {:height "20%" :width "100%"}]
   [:.swiper-slide {:vertical-align "middle"}]
   [:.text-commons {:text-align "center" :font-family "Quicksand, sans-serif" :text-transform "uppercase"
                    :color "white" :margin "20px"}]
   [:.huge-text {:font-size "500%"}]
   [:.normal-text {:font-size "300%" :letter-spacing "10px"}]
   [:.swiper-img {:display "block" :margin "auto"}]))

(def anim-style
  (css
   [:body {:overflow "hidden" :padding "0px" :margin "0px" :background-color "#000000"}]))

(defn face-handler [req]
  (hiccup/html
   [:style anim-style]
   [:body
    [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/three.js/r79/three.min.js"}]
    [:script {:src "ubik/anim/face/main.js"}]]))

(defn bg-handler [req]
  (hiccup/html
   [:style anim-style]
   [:body
    [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/three.js/r79/three.min.js"}]
    [:script {:src "ubik/anim/bg/main.js"}]]))

(defn- get-swiper-container [nr-of-anims swiper-type]
  [:div {:class "swiper-container" :id (str swiper-type "-container")}
   (reduce conj [:div {:class "swiper-wrapper"}]
           (map (fn [i] [:div {:class "swiper-slide"}
                         [:img {:class "swiper-img" :src (str "img/" swiper-type i ".jpg")}]])
                (range nr-of-anims)))])

(defn controller-handler [req]
  (hiccup/html
   [:link {:href "https://fonts.googleapis.com/css?family=Quicksand" :rel "stylesheet"}]
   [:style controller-style]
   (include-css "https://cdnjs.cloudflare.com/ajax/libs/Swiper/3.3.1/css/swiper.min.css")
   (let [face-types ["top" "center" "bottom"]
         get-face-swiper-container (partial get-swiper-container 10)
         main-container (reduce conj [:div {:id "main-container"}] (map get-face-swiper-container face-types))]
     (conj main-container
           (get-swiper-container 5 "bg")
           [:div {:id "desc-container"} [:p {:class "text-commons normal-text"} "swipe them"]]))
   [:div {:id "wait-container"}
    [:div {:id "wait-inner-container"}
     [:p {:id "countdown" :class "text-commons huge-text"}]
     [:p {:class "text-commons normal-text"} "till you get the control"]]]
   [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/Swiper/3.3.1/js/swiper.min.js"}]
   [:script {:src "ubik/controller/main.js"}]))
