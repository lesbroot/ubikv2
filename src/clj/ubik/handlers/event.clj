(ns ubik.handlers.event
  (:require [ubik
             [sente :refer [connected-uids chsk-send!]]
             [scheduler :refer [user-queue calculate-action-timeout last-tick-timestamp start-scheduler!]]]
            [clojure.core.async :as async :refer [<! go-loop put! chan alt!]]
            [taoensso.timbre :refer [debugf]]))

(def anim-types #{:top :center :bottom :bg})

(def event-queue (ref (into {} (map (fn [type] [type clojure.lang.PersistentQueue/EMPTY]) anim-types))))

(def current-anims (atom (into {} (map (fn [type] [type 0]) anim-types))))

(def event-ttl 2000)

(defn broadcast-change-anim! [anim]
  (doseq [uid (:any @connected-uids)]
    (chsk-send! uid [:ubik/change-anim anim])))

(defn set-next-anim! [type]
  (alter event-queue update type pop)
  (when-let [anim (-> @event-queue type peek)]
    (let [{:keys [id]} anim]
      (swap! current-anims assoc type id)
      (broadcast-change-anim! anim))))

(defn process-change-anim-event [{:keys [type id] :as anim}]
  (dosync
   (let [ttl (+ (System/currentTimeMillis) (* (inc (count (@event-queue type))) event-ttl))
         anim-with-uuid (assoc anim :uuid (str (java.util.UUID/randomUUID)) :ttl ttl)]
     (if (= type :bg)
       (broadcast-change-anim! anim-with-uuid)
       (do
         (when (empty? (@event-queue type))
           (swap! current-anims assoc type id)
           (broadcast-change-anim! anim-with-uuid))
         (alter event-queue update type conj anim-with-uuid))))))

(defmulti event-msg-handler :id)

(defmethod event-msg-handler :chsk/uidport-open [{:keys [event ring-req]}]
  (let [uid (get-in ring-req [:params :client-id])]
    (debugf "chsk/uidport-open: %s %s" event uid)
    (chsk-send! uid [:ubik/current-anims @current-anims])))

(defmethod event-msg-handler :ubik/enqueue [{:keys [event ring-req]}]
  (let [uid (get-in ring-req [:params :client-id])]
    (debugf "ubik/enqueue: %s %s" event uid)
    (swap! user-queue conj uid)
    (if (= (count @user-queue) 1)
      (chsk-send! uid [:ubik/start-action @current-anims])
      (chsk-send! uid [:ubik/turn {:action-time (calculate-action-timeout (count @user-queue))}]))))

(defmethod event-msg-handler :chsk/uidport-close [{:keys [event ring-req]}]
  (let [uid (get-in ring-req [:params :client-id])
        closed-idx (count (take-while #(not= uid %) @user-queue))]
    (debugf "chsk/uidport-close: %s %s" event uid)
    (swap! user-queue #(apply conj clojure.lang.PersistentQueue/EMPTY (remove #{%2} %1)) uid) ;;TODO this is O(n)
    (when-let [next-uid (and (= closed-idx 0) (peek @user-queue))]
      (reset! last-tick-timestamp (System/currentTimeMillis))
      (start-scheduler!)
      (chsk-send! next-uid [:ubik/start-action @current-anims]))
    (doseq [[idx uid] (drop closed-idx (map-indexed vector @user-queue))]
      (chsk-send! uid [:ubik/turn {:action-time (calculate-action-timeout (inc idx))}]))))

(defmethod event-msg-handler :ubik/change-anim [{:keys [event ?data ring-req]}]
  (debugf "ubik/change-anim: %s %s" event ?data)
  (let [uid (get-in ring-req [:params :client-id])]
    (when (= (peek @user-queue) uid)
      (process-change-anim-event ?data))))

(defmethod event-msg-handler :ubik/processed-anim [{{:as anim :keys [type id uuid]} :?data}]
  (debugf "ubik/processed-anim: %s" anim)
  (dosync
   (when (= uuid (-> @event-queue type peek :uuid))
     (set-next-anim! type))))

(defmethod event-msg-handler :default [{:as ev-msg :keys [event ring-req]}]
  (let [uid (get-in ring-req [:params :client-id])]
    (debugf "unhandled event: %s %s" event uid)))

(defn event-ttl-scheduler []
  (let [poison-ch (chan)]
    (go-loop []
      (<! (async/timeout (/ event-ttl 2)))
      (when (alt! poison-ch false :default :keep-going)
        (dosync
         (doseq [[type q] @event-queue]
           (when-let [head-event (peek q)]
             (when (< (:ttl head-event) (System/currentTimeMillis))
               (set-next-anim! type)))))
        (recur)))
    poison-ch))

(defonce event-ttl-scheduler-poison-ch (atom nil))
(defn stop-event-ttl-scheduler! [] (when-let [ch @event-ttl-scheduler-poison-ch] (put! ch :stop)))
(defn start-event-ttl-scheduler! []
  (stop-event-ttl-scheduler!)
  (reset! event-ttl-scheduler-poison-ch (event-ttl-scheduler)))