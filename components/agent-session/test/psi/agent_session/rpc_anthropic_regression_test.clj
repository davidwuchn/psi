(ns psi.agent-session.rpc-anthropic-regression-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.rpc :as rpc]))

(defn- run-loop
  ([input handler]
   (run-loop input handler (atom {}) 0))
  ([input handler state]
   (run-loop input handler state 0))
  ([input handler state wait-ms]
   (let [out (java.io.StringWriter.)
         err (java.io.StringWriter.)]
     (rpc/run-stdio-loop! {:in              (java.io.StringReader. input)
                           :out             out
                           :err             err
                           :state           state
                           :request-handler handler})
     (when (pos? wait-ms)
       (Thread/sleep wait-ms))
     {:out-lines (->> (str/split-lines (str out))
                      (remove str/blank?)
                      vec)
      :err-text  (str err)
      :state     @state})))

(defn- parse-frames [lines]
  (mapv edn/read-string lines))

(deftest rpc-new-session-rehydrates-agent-messages-not-tui-projection-test
  (testing "new_session emits canonical agent messages so the next Anthropic request preserves role/content shape"
    (let [ctx (session/create-context)
          state (atom {:ready? true
                       :pending {}
                       :subscribed-topics #{"session/rehydrated"}
                       :on-new-session! (fn []
                                          {:agent-messages [{:role "assistant"
                                                             :content [{:type :text :text "[New session started]"}]}
                                                            {:role "user"
                                                             :content [{:type :text :text "who are you?"}]}]
                                           :messages [{:role :assistant :text "[New session started]"}
                                                      {:role :user :text "who are you?"}]
                                           :tool-calls {}
                                           :tool-order []})})
          handler (rpc/make-session-request-handler ctx)
          input (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                     "{:id \"n1\" :kind :request :op \"new_session\"}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames (parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "[New session started]"}]}
              {:role "user"
               :content [{:type :text :text "who are you?"}]}]
             (get-in rehydrate-event [:data :messages]))))))

(deftest rpc-resume-session-rehydrates-agent-messages-not-tui-projection-test
  (testing "resume emits canonical agent messages from the journal"
    (let [cwd     (str (System/getProperty "java.io.tmpdir") "/psi-rpc-anthropic-resume-" (java.util.UUID/randomUUID))
          _       (.mkdirs (java.io.File. cwd))
          ctx     (session/create-context {:cwd cwd})
          _       (session/new-session-in! ctx)
          path1   (:session-file (ss/get-session-data-in ctx))
          _       (persist/flush-journal! (java.io.File. path1)
                                          (:session-id (ss/get-session-data-in ctx))
                                          cwd
                                          nil
                                          nil
                                          [(persist/message-entry {:role "assistant"
                                                                   :content [{:type :text :text "[New session started]"}]})
                                           (persist/message-entry {:role "user"
                                                                   :content [{:type :text :text "who are you?"}]})])
          state   (atom {:ready? true
                         :pending {}
                         :subscribed-topics #{"session/rehydrated"}})
          handler (rpc/make-session-request-handler ctx)
          input   (str "{:id \"h1\" :kind :request :op \"handshake\" :params {:client-info {:protocol-version \"1.0\"}}}\n"
                       "{:id \"c1\" :kind :request :op \"command\" :params {:text \"/resume " path1 "\"}}\n")
          {:keys [out-lines]} (run-loop input handler state)
          frames (parse-frames out-lines)
          rehydrate-event (some #(when (= "session/rehydrated" (:event %)) %) frames)]
      (is (some? rehydrate-event))
      (is (= [{:role "assistant"
               :content [{:type :text :text "[New session started]"}]}
              {:role "user"
               :content [{:type :text :text "who are you?"}]}]
             (get-in rehydrate-event [:data :messages]))))))
