(ns react-tutorial-om.ranking
  (:require [react-tutorial-om.schemas :as sch]
            [schema.core :as s]))

(def DefaultRank
  {:loses 0
   :wins 0
   :draw 0
   :points 0
   :for 0
   :against 0
   :diff 0
   :matches []})

(defn new-DefaultRank [team]
  (assoc DefaultRank :team team))

(defn match->winner-match [match]
  {:date (:date match)
   :for (:winner-score match)
   :against (:loser-score match)
   :opposition (:loser match)
   :round (:round match)})

(defn match->loser-match [match]
  {:date (:date match)
   :for (:loser-score match)
   :against (:winner-score match)
   :opposition (:winner match)
   :round (:round match)})

(defn sort-ranks [ranks]
  (reverse (sort-by (juxt :points :diff) ranks)))

(s/defn ^:always-validate process-league-match :- {s/Str sch/LeagueRanking}
  "Update rankings based on match. Create a rank for a team if it
  doesn't exist"
  [rankings :- {s/Str sch/LeagueRanking}
   match :- sch/Result]
  (let [winner (:winner match)
        loser (:loser match)
        rankings (if (contains? rankings winner)
                   rankings
                   (assoc rankings winner (new-DefaultRank winner)))
        rankings (if (contains? rankings loser)
                   rankings
                   (assoc rankings loser (new-DefaultRank loser)))]
    (-> rankings
        (update-in [winner :wins] inc)
        (update-in [winner :points] inc)
        (update-in [winner :for] + (:winner-score match))
        (update-in [winner :against] + (:loser-score match))
        (update-in [winner :diff] + (- (:winner-score match) (:loser-score match)))
        (update-in [winner :matches] conj (match->winner-match match))
        (update-in [loser :loses] inc)
        (update-in [loser :against] + (:winner-score match))
        (update-in [loser :for] + (:loser-score match))
        (update-in [loser :diff] + (- (:loser-score match) (:winner-score match)))
        (update-in [loser :matches] conj (match->loser-match match)))))

(s/defn ^:always-validate matches->league-ranks :- [sch/LeagueRanking]
  "Turn raw matches into per player rankings"
  [matches :- [sch/Result]]
  (sort-ranks (vals (reduce process-league-match {} matches))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:private true} mult (first [*' *]))
(def ^{:private true} minus (first [-' -]))

(defn- expt-int [base pow]
  (loop [n pow, y (num 1), z base]
    (let [t (even? n), n (quot n 2)]
      (cond
        t (recur n y (mult z z))
        (zero? n) (mult z y)
        :else (recur n (mult z y) (mult z z))))))

(defn expt
  "(expt base pow) is base to the pow power.
  Returns an exact number if the base is an exact number and the power
  is an integer, otherwise returns a double."
  [base pow]
  (if (and (not (float? base)) (integer? pow))
    (cond
      (pos? pow) (expt-int base pow)
      (zero? pow) 1
      :else (/ 1 (expt-int base (minus pow))))
    (Math/pow base pow)))

;; Below based on https://github.com/mneedham/ranking-algorithms
;; Copyright 2013 Mark Needham
;; Distributed under the Eclipse Public License, the same as Clojure.

(defn expected
  [my-ranking opponent-ranking]
  (/ 1.0
     (+ 1
        (expt 10 (/ (- opponent-ranking my-ranking) 400)))))

(defn ranking-after-game
  [{:keys [ranking opponent-ranking score]}]
  (+ ranking
     (* 32
        (- score (expected ranking opponent-ranking)))))

(defn ranking-after-loss [args]
  (ranking-after-game (merge args {:score 0})))

(defn ranking-after-win [args]
  (ranking-after-game (merge args {:score 1})))

(defn ranking-after-draw [args]
  (ranking-after-game (merge args {:score 0.5})))

(defn process-match [ts match]
  (let [{:keys [home away home-score away-score round]} match]
    (cond
      (> home-score away-score)
      (-> ts
          (update-in [home :points] #(ranking-after-win {:ranking %
                                                         :opponent-ranking (:points (get ts away))}))
          (update-in [away :points] #(ranking-after-loss {:ranking %
                                                          :opponent-ranking (:points (get ts home))})))
      (> away-score home-score)
      (-> ts
          (update-in [home :points] #(ranking-after-loss {:ranking %
                                                          :opponent-ranking (:points (get ts away))}))
          (update-in [away :points] #(ranking-after-win {:ranking %
                                                         :opponent-ranking (:points (get ts home))})))
      (= home-score away-score)
      (-> ts
          (update-in [home :points] #(ranking-after-draw {:ranking %
                                                          :opponent-ranking (:points  (get ts away))}))
          (update-in [away :points] #(ranking-after-draw {:ranking %
                                                          :opponent-ranking (:points (get ts home))}))))))

(defn extract-teams [matches]
  (->> matches
       (mapcat (fn [match] [(:home match) (:away match)]))
       set))

(defn initial-rankings [teams]
  (apply array-map (mapcat (fn [x] [x {:points 1200.00}]) teams)))

(defn merge-rankings [base-rankings initial-rankings]
  (merge initial-rankings
         (into {} (filter #(contains? initial-rankings (key %)) base-rankings))))

(defn merge-keep-left [left right]
  (select-keys (merge left right) keys left))

(defn rank-teams
  ([matches] (rank-teams matches {}))
  ([matches base-rankings]
   (let [teams-with-rankings
         (merge-rankings
          base-rankings
          (initial-rankings (extract-teams matches)))]
     (map
      (fn [[team details]]
        [team (read-string (format "%.2f" (:points details)))])
      (sort-by #(:points (val %))
               >
               (reduce process-match teams-with-rankings matches))))))

(defn top-teams
  ([number matches] (top-teams number matches {}))
  ([number matches base-rankings]
   (take number (rank-teams matches base-rankings))))

(defn show-opposition [team match]
  (if (= team (:home match))
    {:opposition (:away match) :for (:home-score match)
     :against (:away-score match) :round (:round match) :date (:date match)}
    {:opposition (:home match) :for (:away-score match)
     :against (:home-score match) :round (:round match) :date (:date match)}))

(defn show-matches [team matches]
  (->> matches
       (filter #(or (= team (:home %)) (= team (:away %))))
       (map #(show-opposition team %))))

(defn show-opponents [team matches rankings]
  (map #(merge (get rankings (:opposition %)) %)
       (show-matches team matches)))

(defn performance [opponents]
  (let [last-match (last opponents)]
    (:round last-match)))

(defn match-record [opponents]
  {:wins   (count (filter #(> (:for %) (:against %)) opponents))
   :draw   (count (filter #(= (:for %) (:against %)) opponents))
   :loses  (count (filter #(< (:for %) (:against %)) opponents))})

(defn format-for-printing [all-matches idx [team ranking & [rd]]]
  (let [team-matches (show-matches team all-matches)]
    (merge {:rank (inc idx)
            :team team
            :ranking ranking
            :rd rd
            :round (performance team-matches)}
           (match-record team-matches))))
