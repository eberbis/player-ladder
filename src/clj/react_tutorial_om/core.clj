(ns react-tutorial-om.core
  (:require [clj-http.client :as client]
            [clj-time.coerce :refer [from-date from-string]]
            [clj-time.core :as time]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [compojure.api.routes :refer [with-routes]]
            [compojure.api.sweet
             :refer
             [GET* POST* context swagger-docs swagger-ui swaggered]]
            [compojure.core :refer [GET]]
            [compojure.route :as route]
            [com.stuartsierra.component :as component]
            [net.cgrand.enlive-html :refer [append deftemplate html prepend set-attr]]
            [prone.debug :refer [debug]]
            [prone.middleware :as prone]
            [ranking-algorithms.core :as rank]
            [react-tutorial-om.ranking :as ranking]
            [react-tutorial-om.schemas :as sch]
            ring.adapter.jetty
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.util.http-response :as http-resp :refer [ok]]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+ try+]]))

(def inject-devmode-html
  (comp
     (set-attr :class "is-dev")
     (prepend (html [:script {:type "text/javascript" :src "/js/out/goog/base.js"}]))
     ;; (prepend (html [:script {:type "text/javascript" :src "/react/react.js"}]))
     (append  (html [:script {:type "text/javascript"} "goog.require('react_tutorial_om.app')"]))))

(deftemplate page (io/resource "public/index.html")
  [is-dev?]
  [:body] (if is-dev? inject-devmode-html identity))

(defn recent? [date & [now]]
  (if (nil? date)
    false
    (let [joda-date (or (from-string date) (from-date date))
          offset (time/weeks 4)
          now (or now (time/now))]
      (if (nil? joda-date)
        false
        (time/after? joda-date
                     (time/minus now offset))))))

(defn load-edn-file [file]
  (-> (slurp file)
      (edn/read-string)
      ((partial s/validate sch/AllResults))))

(defn spit-edn-file [file data]
  (spit file (with-out-str (pprint data))))

(defn init!
  [db db-file]
  (reset! db (load-edn-file db-file)))


(s/defn ^:always-validate update-ladder-match
  "Coerce the data into the format we want and add date"
  [match :- sch/Result]
  (-> match
      ;; TODO: coerce data earlier
      (update-in [:winner] clojure.string/lower-case)
      (update-in [:loser] clojure.string/lower-case)
      (assoc :date (java.util.Date.))))

(s/defn ^:always-validate update-ladder-results :- sch/AllResults
  [match :- sch/Result
   doc :- sch/AllResults]
  (update-in doc [:singles-ladder] conj (update-ladder-match match)))

(s/defn ^:always-validate  save-match! ;; TODO: write to a db
  [match :- sch/Result, db ]
  (swap! db (partial update-ladder-results match))
  {:message "Saved Match!"})

(defn translate-keys [{:keys [winner winner-score loser loser-score date]}]
  {:home winner
   :home_score winner-score
   :away loser
   :away_score loser-score
   :date date})

(defn calc-ranking-data [matches]
  (map-indexed
   (partial rank/format-for-printing matches)
   (rank/top-teams 30 matches)
   #_(do (prn (rank/top-glicko-teams 30 matches))
         (rank/top-glicko-teams 30 matches {}))))

(defn attach-player-matches [results rankings]
  (for [rank rankings]
    (assoc-in rank [:matches] (rank/show-matches (:team rank) results))))

(defn normalise-indexes
  "Move out of bounds indexes into the next free position

  10 2 [-2 -1 1 2] => (4 3 1 2)
  10 2 [7 8 10 11] => (7 8 5 6)"
  [total offset idxs]
  (for [idx idxs]
    (cond
      (neg? idx) (- offset idx)
      (> (inc idx) total) (- (dec idx) offset offset)
      :else idx)))

(defn suggest-opponent
  "Given a user match history and map of user ranks suggest the next
  oppenent a user should face. Ranks is a vector of people.

  TODO: tidy up"
  [{:keys [rank matches]} ranks]
  (try
    (let [offset 2
          idx-rank (dec rank)
          opps1 (range rank (+ rank offset))
          opps2 (range (- idx-rank offset) idx-rank)
          allopps  (->> (concat opps1 opps2)
                        (normalise-indexes (count ranks) offset))
          oppnames-set (set (map ranks allopps))
          matchfreqs (frequencies (filter #(contains? oppnames-set %)
                                          (map :opposition matches)))
          near-totals (reduce (fn [acc [k v]] (assoc acc k v))
                              (zipmap oppnames-set (repeat 0)) ;; Start at 0
                              matchfreqs)
          sorted-totals (sort-by second near-totals)]
      (ffirst sorted-totals))
    (catch IndexOutOfBoundsException e
      (println "Error in suggest oponent" e)
      "")))

(defn- vectorise-names [rankings]
  (vec (map :team rankings)))

(defn attach-suggested-opponents
  [rankings]
  (let [vec-ranks (vectorise-names rankings)]
    (for [rank rankings]
      (assoc-in rank [:suggest] (suggest-opponent rank vec-ranks)))))

(defn attach-uniques [rankings]
  (for [rank rankings]
    (->> rank
         :matches
         (filter (fn [x] (> (:for x) (:against x))))
         (map :opposition)
         (into #{})
         count
         (assoc-in rank [:u-wins]))))

(defn unique-players [results]
  (into (into #{} (map :home results))
        (map :away results)))

(defn pdbug [x]
  (println x)
  #_(doseq [t x]
      (println (:team t)))
  (println (filter #(= (:team %) "jons") x))
  x)



(defn handle-rankings
  [results]
  {:message "Some rankings"
   :players (unique-players results)
   :rankings  (->> (calc-ranking-data results)
                   (attach-player-matches results)
                   attach-suggested-opponents
                   attach-uniques
                   #_(filter (fn [{matches :matches}]
                               (recent? (:date (last matches)))))
                   #_(filter (fn [{:keys [loses wins]}] (> (+ loses wins) 4)))
                   ((fn [col] (if (> (count col) 5)
                               (drop-last 2 col)
                               col)))
                   (map-indexed (fn [i m] (assoc m :rank (inc i)))))})

(s/defn ^:always-validate update-league-result
  "Update the state map with the result of the league match. Remove
  match from schedule and also update the ladder with the result"
  [result :- sch/LeagueResult league doc]
  (-> doc
      (update-in [:leagues league :schedule]
                 (fn [sch] (remove #(= (:id %) (:id result)) sch)))
      (update-in [:leagues league :matches]
                 conj (assoc result :date (java.util.Date.)))
      ((partial update-ladder-results (dissoc result :id :round)))))

(defn handle-league-result
  "Given an db atom, a league name and a result update the schedule and
  matches for the league and write out to file. Return the resulting
  state. Also post to slack if possible."
  [db league {:keys [winner loser winner-score loser-score] :as result} slack-url]
  (swap! db (partial update-league-result result league))
  (when-not (str/blank? slack-url)
    (future
      (try+
       (client/post slack-url
                    {:form-params {:text (format "%s wins against %s in league %s: %s - %s"
                                                 winner loser (name league) winner-score loser-score)}
                     :content-type :json})
       (catch [:status 403] {:keys [request-time headers body]}
         (println "Slack 403 " request-time headers))
       (catch Object _
         (println (:throwable &throw-context) "unexpected error")))))
  {:message "ok"})

(defn make-routes [is-dev? db slack-url]
  (with-routes
    (route/resources "/")
    (route/resources "/react" {:root "react"})
    (swagger-ui :swagger-docs "/api/docs")
    (swagger-docs "/api/docs")
    (GET "/app" [] (apply str (page is-dev?)))
    (GET "/init" [] (init! db) "inited")
    (swaggered
     "matches"
     :description "Matches"
     (context
      "/matches" []
      (GET* "/" []
            :return {:message s/Str
                     :matches [{s/Keyword s/Any}]}
            :summary "all the matches"
            (ok
             {:message "Here's the results!"
              :matches (take-last 20 (:singles-ladder @db))}))
      (POST* "/" req
             :body [result sch/Result]
             (ok (save-match! result db)))))
    (swaggered
     "rankings"
     :description "Rankings"
     (context
      "/rankings" []
      (GET* "/" []
            :return sch/RankingsResponse
            (ok
             (handle-rankings (map translate-keys (:singles-ladder @db)))))))
    (swaggered
     "leagues"
     :description "Leagues"
     (context
      "/leagues" []
      (GET* "/" []
            :return sch/LeaguesResponse
            (ok
             {:leagues (into {} (for [[l {:keys [matches schedule name]}] (:leagues @db)]
                                  [l {:rankings (ranking/matches->league-ranks matches)
                                      :schedule schedule
                                      :name name}]))}))
      (POST* "/:league/result" [league] ;TODO: take id in post url?
             :body [result sch/LeagueResult]
             (ok (handle-league-result db (keyword league) result slack-url)))))
    (route/not-found "Page not found")))

(defn wrap-schema-errors [handler]
  (fn [req]
    (try+
     (handler req)
     (catch [:type :ring.swagger.schema/validation] {:keys [error] :as all}
       (println all)
       (http-resp/bad-request {:error error})))))

(defn log-request-middleware [handler]
  (fn [request]
                                        ;(puget.printer/pprint request)
    (let [res (handler request)]
      #_(println res)
      res)))

(defn make-handler [is-dev? db slack-url]
  (-> (make-routes is-dev? db slack-url)
      ;; log-request-middleware

      (cond-> is-dev? (prone/wrap-exceptions
                       {:app-namespaces ["react-tutorial-om"]
                        :skip-prone? (fn [{:keys [headers]}]
                                       (println headers)
                                       (contains? headers "postman-token"))}))
      compojure.api.middleware/api-middleware
      (wrap-restful-format :formats  [:json :transit-json])

      ;; wrap-schema-errors
      ;; ring.swagger.middleware/catch-validation-errors
      ;; ring.middleware.http-response/catch-response
      ))

(defrecord WebServer [ring is-dev? slack-url]
  component/Lifecycle
  (start [component]
    (let [db (atom {})
          db-file "results.edn"
          file-agent (agent nil :error-handler println)
          _ (init! db "results.edn")
          app (make-handler is-dev? db slack-url)]
      (add-watch db :writer (fn [_ _ _ new]
                              (send-off file-agent (fn [_] (spit-edn-file db-file new)))))
      #_(when is-dev?
          (inspect/start))
      (assoc component
             :server
             (ring.adapter.jetty/run-jetty app ring)
             :file-agent file-agent
             :db db)))
  (stop [component]
    #_(when is-dev?
        (inspect/stop))
    (when-let [server (:server component)]
      (.stop server))
    (assoc component :server nil)))

(defn new-webserver [config]
  (map->WebServer config))


(comment
  "
 TODO:
* use a db => datomic?
* sort by col
* click on player -> match history, nemesis,
* proper glicko
* generic sortable table component
* unique wins, etc - some kind of distribution concept, who is most rounded
* can only play against people near you (+/- 3). how to handle top people?
* date in tooltip
* notification of results (chrome notification?), only if not entered here
 ")
