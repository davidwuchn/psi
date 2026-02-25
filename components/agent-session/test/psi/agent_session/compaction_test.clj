(ns psi.agent-session.compaction-test
  "Tests for compaction preparation and stub execution."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.session :as session]
   [psi.agent-session.persistence :as persist]))

(defn- session-with-entries
  "Build a session data map with `n` fake message entries."
  [n]
  (let [entries (mapv (fn [i]
                        (persist/message-entry
                         {:role "user" :content [{:type :text :text (str "msg " i)}]}))
                      (range n))]
    (assoc (session/initial-session)
           :session-entries entries
           :context-tokens  50000
           :context-window  100000)))

;; ── prepare-compaction ──────────────────────────────────────────────────────

(deftest prepare-compaction-test
  (testing "keeps last keep-count entries"
    (let [sd   (session-with-entries 15)
          prep (compaction/prepare-compaction sd 10)]
      (is (= 5 (count (:entries-to-summarise prep))))
      (is (string? (:first-kept-entry-id prep)))))

  (testing "keeps all entries when n <= keep-count"
    (let [sd   (session-with-entries 5)
          prep (compaction/prepare-compaction sd 10)]
      (is (= 0 (count (:entries-to-summarise prep))))
      ;; first-kept-entry-id is the first entry's id when all are kept
      (is (= (get-in sd [:session-entries 0 :id])
             (:first-kept-entry-id prep)))))

  (testing "tokens-before mirrors context-tokens"
    (let [sd   (session-with-entries 20)
          prep (compaction/prepare-compaction sd 10)]
      (is (= 50000 (:tokens-before prep))))))

;; ── stub-compaction-fn ──────────────────────────────────────────────────────

(deftest stub-compaction-fn-test
  (testing "returns CompactionResult with summary string"
    (let [sd   (session-with-entries 10)
          prep (compaction/prepare-compaction sd 5)
          res  (compaction/stub-compaction-fn sd prep nil)]
      (is (string? (:summary res)))
      (is (re-find #"stub" (:summary res)))
      (is (= (:first-kept-entry-id prep) (:first-kept-entry-id res)))
      (is (= (:tokens-before prep) (:tokens-before res)))
      (is (nil? (:details res)))))

  (testing "summary mentions entry count"
    (let [sd   (session-with-entries 12)
          prep (compaction/prepare-compaction sd 5)
          res  (compaction/stub-compaction-fn sd prep nil)]
      (is (re-find #"7" (:summary res))))))

;; ── rebuild-messages-from-entries ──────────────────────────────────────────

(deftest rebuild-messages-test
  (testing "returns summary message when no kept entries"
    (let [sd     (session-with-entries 5)
          _prep  (compaction/prepare-compaction sd 0)
          result {:summary "All summarised."
                  :first-kept-entry-id nil
                  :tokens-before 50000}
          msgs   (compaction/rebuild-messages-from-entries result sd)]
      (is (= 1 (count msgs)))
      (is (re-find #"Previous conversation summary" (get-in msgs [0 :content 0 :text])))))

  (testing "includes messages from kept entries after summary"
    (let [sd       (session-with-entries 5)
          kept-id  (get-in sd [:session-entries 3 :id])
          result   {:summary "Summary."
                    :first-kept-entry-id kept-id
                    :tokens-before 50000}
          msgs     (compaction/rebuild-messages-from-entries result sd)]
      ;; summary msg + msgs from entry 3 and 4
      (is (= 3 (count msgs)))
      (is (re-find #"Summary" (get-in msgs [0 :content 0 :text]))))))
