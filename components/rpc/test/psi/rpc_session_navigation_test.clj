(ns psi.rpc-session-navigation-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-state :as ss]
   [psi.rpc-test-support :as support]))

(deftest rpc-session-resume-and-rehydrate-events-test
  (testing "new_session emits session/resumed and session/rehydrated canonical events"
    (let [[ctx session-id]    (support/create-session-context)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"n1\" :kind :request :op \"new_session\" :params {:session-id \"" session-id "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some #(contains? (:data %) :session-id) events))
      (is (some #(contains? (:data %) :messages) events))))

  (testing "command /new emits session/resumed and session/rehydrated canonical events"
    (let [[ctx _]             (support/create-session-context {:session-defaults {:model {:provider "openai"
                                                                                                  :id "gpt-5.4"
                                                                                                  :reasoning false}}})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result" "footer/updated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/new\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))
          command-result      (some #(when (= "command-result" (:event %)) %) events)
          footer-event        (some #(when (= "footer/updated" (:event %)) %) events)
          stats-line          (get-in footer-event [:data :stats-line] "")]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (some? command-result))
      (is (= "new_session" (get-in command-result [:data :type])))
      (is (some? footer-event))
      (is (str/includes? stats-line "gpt-5.4"))
      (is (str/includes? stats-line "(openai)"))
      (is (not (str/includes? stats-line "(no-provider)")))))

  (testing "command /resume <path> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-resume-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (support/create-session-context {:cwd cwd})
          sd1                 (session/new-session-in! ctx nil {})
          session-id          (:session-id sd1)
          path1               (:session-file sd1)
          _                   (persist/flush-journal! (java.io.File. path1)
                                                      session-id
                                                      cwd
                                                      nil
                                                      nil
                                                      [(persist/message-entry {:role "user" :content "hi"})
                                                       (persist/message-entry {:role "assistant" :content [{:type :text :text "there"}]})])
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= path1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                           [:data :session-file])))
      (is (= [{:role "user" :content "hi"}
              {:role "assistant" :content [{:type :text :text "there"}]}]
             (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                     [:data :messages])))))

  (testing "command /tree <session-id> emits session/resumed and session/rehydrated canonical events"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx _]             (support/create-session-context {:cwd cwd})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          path1               (:session-file sd1)
          _                   (persist/flush-journal! (java.io.File. path1)
                                                      sid1
                                                      cwd
                                                      nil
                                                      nil
                                                      [(persist/message-entry {:role "assistant" :content [{:type :text :text "root"}]})])
          _                   (session/new-session-in! ctx sid1 {})
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated" "command-result"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree " sid1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          events              (filter #(= :event (:kind %)) frames)
          event-topics        (set (map :event events))]
      (is (contains? event-topics "session/resumed"))
      (is (contains? event-topics "session/rehydrated"))
      (is (= sid1 (get-in (some #(when (= "session/resumed" (:event %)) %) events)
                          [:data :session-id])))
      (is (vector? (get-in (some #(when (= "session/rehydrated" (:event %)) %) events)
                           [:data :messages])))))

  (testing "command /tree emits frontend selector payload with backend-owned order"
    (let [cwd                 (str (System/getProperty "java.io.tmpdir") "/psi-rpc-tree-picker-" (java.util.UUID/randomUUID))
          _                   (.mkdirs (java.io.File. cwd))
          [ctx session-id]    (support/create-session-context {:cwd cwd})
          child-sd            (session/new-session-in! ctx session-id {})
          child-id            (:session-id child-sd)
          _                   (ss/update-state-value-in! ctx
                                                         (ss/state-path :session-data child-id)
                                                         assoc
                                                         :parent-session-id session-id)
          _                   (session/set-session-name-in! ctx session-id "main")
          _                   (session/set-session-name-in! ctx child-id "child")
          entry               (persist/message-entry {:role    "user"
                                                      :content [{:type :text :text "Branch from this prompt"}]})
          _                   (ss/journal-append-in! ctx session-id entry)
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:focus-session-id session-id
                                                  :subscribed-topics #{"ui/frontend-action-requested"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/tree\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          action-evt          (some #(when (= "ui/frontend-action-requested" (:event %)) %) frames)
          selector            (get-in action-evt [:data :ui/action])
          items               (mapv :ui.item/meta (:ui/items selector))
          fork-slot           (some #(when (= :fork-point (:item/kind %)) %) items)
          main-idx            (first (keep-indexed (fn [i item]
                                                     (when (and (= :session (:item/kind item))
                                                                (= session-id (:item/session-id item)))
                                                       i))
                                                   items))
          fork-idx            (first (keep-indexed (fn [i item]
                                                     (when (= (:id entry) (:item/entry-id item)) i))
                                                   items))]
      (is (some? action-evt))
      (is (= "select-session" (get-in action-evt [:data :action-name])))
      (is (= :select-session (:ui/action-name selector)))
      (is (vector? items))
      (is (some? fork-slot))
      (is (= (:id entry) (:item/entry-id fork-slot)))
      (is (= "Branch from this prompt" (:item/display-name fork-slot)))
      (is (some? main-idx))
      (is (some? fork-idx))
      (is (< main-idx fork-idx)
          "backend payload must place current-session fork points after their containing session")))

  (testing "switch_session and get_messages derive transcript from ctx journal when agent messages drift"
    (let [[ctx _]             (support/create-session-context {:persist? false})
          sd1                 (session/new-session-in! ctx nil {})
          sid1                (:session-id sd1)
          canonical-messages  [{:role "user" :content "who are you?"}
                               {:role "assistant" :content [{:type :text :text "I am psi."}]}]
          _                   (doseq [m canonical-messages]
                                (ss/journal-append-in! ctx sid1 (persist/message-entry m)))
          _                   (agent/replace-messages-in! (ss/agent-ctx-in ctx sid1)
                                                          [{:role    "assistant"
                                                            :content [{:type :text :text "stale in-memory tail"}]}])
          state               (atom {:transport {:ready? true :pending {}}
                                     :connection {:subscribed-topics #{"session/resumed" "session/rehydrated"}}})
          handler             (support/make-handler ctx state)
          input               (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                                   "{:id \"s1\" :kind :request :op \"switch_session\" :params {:session-id \"" sid1 "\"}}\n"
                                   "{:id \"g1\" :kind :request :op \"get_messages\" :params {:session-id \"" sid1 "\"}}\n")
          {:keys [out-lines]} (support/run-loop input handler state)
          frames              (support/parse-frames out-lines)
          rehydrate-event     (some #(when (and (= :event (:kind %))
                                                (= "session/rehydrated" (:event %))) %)
                                    frames)
          get-messages-resp   (some #(when (and (= :response (:kind %))
                                                (= "get_messages" (:op %))) %)
                                    frames)]
      (is (= canonical-messages
             (get-in rehydrate-event [:data :messages])))
      (is (= canonical-messages
             (get-in get-messages-resp [:data :messages]))))))
