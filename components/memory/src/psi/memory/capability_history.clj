(ns psi.memory.capability-history)

(defn- normalize-capability-ids
  [capability-ids]
  (->> (or capability-ids [])
       (keep (fn [capability-id]
               (cond
                 (keyword? capability-id) capability-id
                 (string? capability-id) capability-id
                 :else nil)))
       (distinct)
       (vec)))

(defn events-for-record
  [record]
  (let [provenance        (:provenance record)
        capability-ids    (normalize-capability-ids (:capabilityIds provenance))
        source            (or (:source provenance)
                              (:source-type provenance)
                              :unknown)
        graph-fingerprint (:graphFingerprint provenance)]
    (mapv (fn [capability-id]
            {:event-id (str (random-uuid))
             :event-type :memory-linked
             :timestamp (:timestamp record)
             :capability-id capability-id
             :record-id (:record-id record)
             :content-type (:content-type record)
             :source source
             :graph-fingerprint graph-fingerprint})
          capability-ids)))

(defn events-for-baseline-snapshot
  [snapshot]
  (mapv (fn [capability-id]
          {:event-id (str (random-uuid))
           :event-type :graph-capability-baseline
           :timestamp (:timestamp snapshot)
           :capability-id capability-id
           :graph-fingerprint (:fingerprint snapshot)})
        (normalize-capability-ids (:capability-ids snapshot))))

(defn events-for-delta
  [delta]
  (let [timestamp        (:timestamp delta)
        from-fingerprint (:from-fingerprint delta)
        to-fingerprint   (:to-fingerprint delta)
        added-events     (mapv (fn [capability-id]
                                 {:event-id (str (random-uuid))
                                  :event-type :graph-capability-added
                                  :timestamp timestamp
                                  :capability-id capability-id
                                  :from-fingerprint from-fingerprint
                                  :to-fingerprint to-fingerprint})
                               (normalize-capability-ids (:added-capability-ids delta)))
        removed-events   (mapv (fn [capability-id]
                                 {:event-id (str (random-uuid))
                                  :event-type :graph-capability-removed
                                  :timestamp timestamp
                                  :capability-id capability-id
                                  :from-fingerprint from-fingerprint
                                  :to-fingerprint to-fingerprint})
                               (normalize-capability-ids (:removed-capability-ids delta)))]
    (into [] (concat added-events removed-events))))

(defn events-for-recovery
  [recovery]
  (let [timestamp      (:timestamp recovery)
        recovery-id    (:recovery-id recovery)
        query-text     (get-in recovery [:filters :query-text])
        requested-ids  (normalize-capability-ids (get-in recovery [:filters :capability-ids]))
        requested-set  (set requested-ids)]
    (into []
          (mapcat (fn [record]
                    (let [provenance         (:provenance record)
                          record-ids         (normalize-capability-ids (:capabilityIds provenance))
                          hit-capability-ids (if (seq requested-set)
                                               (filterv requested-set record-ids)
                                               record-ids)
                          source             (or (:source provenance)
                                                 (:source-type provenance)
                                                 :unknown)]
                      (mapv (fn [capability-id]
                              {:event-id (str (random-uuid))
                               :event-type :recovery-hit
                               :timestamp timestamp
                               :capability-id capability-id
                               :recovery-id recovery-id
                               :record-id (:record-id record)
                               :query-text query-text
                               :source source
                               :recovery-score (:recovery/score record)})
                            hit-capability-ids)))
                  (:results recovery)))))
