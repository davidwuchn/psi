(ns psi.tui.app-test
  "Tests for the charm.clj Elm Architecture TUI.
   Exercises init/update/view as pure functions — no terminal needed.
   Includes a JLine integration smoke test for terminal + keymap creation."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [charm.components.text-input :as text-input]
   [charm.core :as charm]
   [charm.input.keymap :as keymap]
   [charm.message :as msg]
   [psi.tui.app :as app])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

;;;; Helpers

(defn- init-state
  "Create a fresh state from make-init."
  ([] (init-state "test-model"))
  ([model-name]
   (let [init-fn (app/make-init model-name)
         [state _cmd] (init-fn)]
     state)))

(defn- stub-agent-fn
  "A stub run-agent-fn! that immediately puts a done result on the queue."
  [response-text]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :done
                 :result {:role    "assistant"
                          :content [{:type :text :text response-text}]}})))

(defn- error-agent-fn
  "A stub run-agent-fn! that immediately puts an error on the queue."
  [error-msg]
  (fn [_text ^LinkedBlockingQueue queue]
    (.put queue {:kind :error :message error-msg})))

;;;; Init

(deftest init-test
  (testing "init returns idle state with nil cmd"
    (let [init-fn (app/make-init "test-model")
          [state cmd] (init-fn)]
      (is (= :idle (:phase state)))
      (is (nil? cmd))
      (is (empty? (:messages state)))
      (is (nil? (:error state)))
      (is (= "test-model" (:model-name state))))))

;;;; Update — key input

(deftest typing-updates-input-test
  (testing "printable keys update the text input"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "h"))
          [s2 _]    (update-fn s1 (msg/key-press "i"))]
      (is (= "hi" (text-input/value (:input s2)))))))

(deftest backspace-test
  (testing "backspace removes a character"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "a"))
          [s2 _]    (update-fn s1 (msg/key-press "b"))
          [s3 _]    (update-fn s2 (msg/key-press :backspace))]
      (is (= "a" (text-input/value (:input s3)))))))

;;;; Update — submit

(deftest submit-starts-streaming-test
  (testing "enter with text transitions to :streaming"
    (let [submitted (atom nil)
          agent-fn  (fn [text queue]
                      (reset! submitted text)
                      (.put ^LinkedBlockingQueue queue
                            {:kind :done
                             :result {:role "assistant"
                                      :content [{:type :text :text "ok"}]}}))
          update-fn (app/make-update agent-fn)
          state     (init-state)
          [s1 _]    (update-fn state (msg/key-press "g"))
          [s2 _]    (update-fn s1 (msg/key-press "o"))
          [s3 cmd]  (update-fn s2 (msg/key-press :enter))]
      (is (= :streaming (:phase s3)))
      (is (some? cmd))
      (is (= "go" @submitted))
      ;; User message recorded
      (is (= 1 (count (:messages s3))))
      (is (= :user (:role (first (:messages s3))))))))

(deftest submit-blank-does-nothing-test
  (testing "enter with empty input stays idle"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 cmd]  (update-fn state (msg/key-press :enter))]
      (is (= :idle (:phase s1)))
      (is (nil? cmd)))))

;;;; Update — agent results

(deftest agent-result-transitions-to-idle-test
  (testing "agent-result message adds assistant message and goes idle"
    (let [update-fn (app/make-update (stub-agent-fn "hello"))
          state     (init-state)
          ;; Simulate streaming state
          streaming (assoc state :phase :streaming
                                :messages [{:role :user :text "hi"}]
                                :queue (LinkedBlockingQueue.))
          result    {:role "assistant" :content [{:type :text :text "hello"}]}
          [s1 _]    (update-fn streaming {:type :agent-result :result result})]
      (is (= :idle (:phase s1)))
      (is (= 2 (count (:messages s1))))
      (is (= "hello" (:text (second (:messages s1))))))))

(deftest agent-error-transitions-to-idle-test
  (testing "agent-error message sets error and goes idle"
    (let [update-fn (app/make-update (error-agent-fn "boom"))
          streaming (assoc (init-state) :phase :streaming
                                        :queue (LinkedBlockingQueue.))
          [s1 _]    (update-fn streaming {:type :agent-error :error "boom"})]
      (is (= :idle (:phase s1)))
      (is (= "boom" (:error s1))))))

;;;; Update — poll advances spinner

(deftest poll-advances-spinner-test
  (testing "agent-poll increments spinner-frame"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          queue     (LinkedBlockingQueue.)
          streaming (assoc (init-state) :phase :streaming
                                        :queue queue
                                        :spinner-frame 0)
          [s1 cmd]  (update-fn streaming {:type :agent-poll})]
      (is (= 1 (:spinner-frame s1)))
      (is (some? cmd)))))

;;;; Update — quit

(deftest escape-quits-when-idle-test
  (testing "escape when idle returns quit-cmd"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [_s cmd]  (update-fn state (msg/key-press :escape))]
      ;; quit-cmd is a charm command map
      (is (some? cmd)))))

(deftest ctrl-c-always-quits-test
  (testing "ctrl+c quits even during streaming"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (assoc (init-state) :phase :streaming
                                        :queue (LinkedBlockingQueue.))
          [_s cmd]  (update-fn streaming (msg/key-press "c" :ctrl true))]
      (is (some? cmd)))))

(deftest keys-ignored-during-streaming-test
  (testing "printable keys are ignored while streaming"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          streaming (assoc (init-state) :phase :streaming
                                        :queue (LinkedBlockingQueue.))
          [s1 cmd]  (update-fn streaming (msg/key-press "x"))]
      (is (= :streaming (:phase s1)))
      (is (nil? cmd)))))

;;;; View

(deftest view-renders-banner-test
  (testing "view includes model name"
    (let [state (init-state "gpt-4o")
          out   (app/view state)]
      (is (string? out))
      (is (str/includes? out "gpt-4o")))))

(deftest view-renders-messages-test
  (testing "view renders user and assistant messages"
    (let [state (assoc (init-state)
                       :messages [{:role :user :text "hello"}
                                  {:role :assistant :text "world"}])]
      (is (str/includes? (app/view state) "hello"))
      (is (str/includes? (app/view state) "world")))))

(deftest view-shows-spinner-during-streaming-test
  (testing "view shows spinner while streaming"
    (let [state (assoc (init-state) :phase :streaming)]
      (is (str/includes? (app/view state) "thinking")))))

(deftest view-shows-error-test
  (testing "view shows error message"
    (let [state (assoc (init-state) :error "something broke")]
      (is (str/includes? (app/view state) "something broke")))))

;;;; Window resize

(deftest window-resize-updates-dimensions-test
  (testing "window-size message updates width and height"
    (let [update-fn (app/make-update (stub-agent-fn ""))
          state     (init-state)
          [s1 _]    (update-fn state (msg/window-size 120 40))]
      (is (= 120 (:width s1)))
      (is (= 40 (:height s1))))))

;;;; JLine integration smoke test

(deftest jline-terminal-keymap-test
  (testing "JLine terminal + keymap creation works (catches JLine API compat bugs)"
    (let [terminal (charm/create-terminal)]
      (try
        (let [km (keymap/create-keymap terminal)]
          (is (some? km) "keymap created successfully"))
        (finally
          (.close terminal))))))
