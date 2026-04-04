(ns psi.agent-session.user-config-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.user-config :as user-config])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "psi-user-config-test-"
                                      (into-array FileAttribute []))))

(defn- config-file-in [dir]
  (io/file dir ".psi" "agent" "config.edn"))

(deftest user-config-file-test
  ;; Tests the canonical user-global config file location under user.home.
  (testing "builds the config path under the current user.home"
    (let [dir           (tmp-dir)
          original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" (.getAbsolutePath dir))
        (is (= (.getCanonicalPath (config-file-in dir))
               (.getCanonicalPath (user-config/user-config-file))))
        (finally
          (if original-home
            (System/setProperty "user.home" original-home)
            (System/clearProperty "user.home")))))))

(deftest read-config-test
  ;; Tests best-effort config reads across missing, invalid, and valid file states.
  (testing "returns default config when file is missing"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when file contains invalid edn"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "not valid edn\n")
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when slurp throws during read"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "{:agent-session {:model-provider \"anthropic\"}}")
      (with-redefs [user-config/user-config-file (fn [] f)
                    clojure.core/slurp            (fn [_] (throw (ex-info "boom" {})))]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "returns default config when file contains a non-map value"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f "[:not-a-map]\n")
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1 :agent-session {}}
               (user-config/read-config))))))

  (testing "merges valid persisted config with defaults"
    (let [dir (tmp-dir)
          f   (config-file-in dir)
          persisted {:agent-session {:model-provider "anthropic"
                                     :model-id "claude"
                                     :prompt-mode :lambda}}]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str persisted))
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude"
                                :prompt-mode :lambda}}
               (user-config/read-config)))))))

(deftest update-agent-session!-test
  ;; Tests merge-and-persist behavior for user-global agent-session preferences.
  (testing "creates parent directories and persists merged agent-session config"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :thinking-level :high}}
               (user-config/update-agent-session! {:model-provider "anthropic"
                                                   :model-id "claude-3-7"
                                                   :thinking-level :high})))
        (is (.exists f))
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :thinking-level :high}}
               (edn/read-string (slurp f)))))))

  (testing "merges new values into existing persisted agent-session config"
    (let [dir (tmp-dir)
          f   (config-file-in dir)]
      (.mkdirs (.getParentFile f))
      (spit f (pr-str {:version 1
                       :agent-session {:model-provider "anthropic"
                                       :model-id "claude-3-7"
                                       :prompt-mode :prose}}))
      (with-redefs [user-config/user-config-file (fn [] f)]
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :prompt-mode :prose
                                :thinking-level :medium}}
               (user-config/update-agent-session! {:thinking-level :medium})))
        (is (= {:version 1
                :agent-session {:model-provider "anthropic"
                                :model-id "claude-3-7"
                                :prompt-mode :prose
                                :thinking-level :medium}}
               (edn/read-string (slurp f))))))))

(deftest user-model-test
  ;; Tests that a user model is exposed only when both provider and id are valid strings.
  (testing "returns model map when provider and id are strings"
    (is (= {:provider "anthropic" :id "claude-sonnet-4"}
           (user-config/user-model {:agent-session {:model-provider "anthropic"
                                                    :model-id "claude-sonnet-4"}}))))

  (testing "returns nil when provider or id is missing or invalid"
    (is (nil? (user-config/user-model {:agent-session {:model-provider "anthropic"}})))
    (is (nil? (user-config/user-model {:agent-session {:model-id "claude-sonnet-4"}})))
    (is (nil? (user-config/user-model {:agent-session {:model-provider :anthropic
                                                       :model-id "claude-sonnet-4"}})))
    (is (nil? (user-config/user-model {:agent-session {:model-provider "anthropic"
                                                       :model-id :claude}})))))

(deftest user-thinking-level-test
  ;; Tests that thinking level accepts keywords and rejects non-keywords.
  (testing "returns keyword thinking level"
    (is (= :high
           (user-config/user-thinking-level {:agent-session {:thinking-level :high}}))))

  (testing "returns nil for non-keyword thinking level"
    (is (nil? (user-config/user-thinking-level {:agent-session {:thinking-level "high"}})))))

(deftest user-prompt-mode-test
  ;; Tests prompt mode validation for the allowed mode set.
  (testing "returns allowed prompt modes"
    (is (= :lambda
           (user-config/user-prompt-mode {:agent-session {:prompt-mode :lambda}})))
    (is (= :prose
           (user-config/user-prompt-mode {:agent-session {:prompt-mode :prose}}))))

  (testing "returns nil for unsupported prompt modes"
    (is (nil? (user-config/user-prompt-mode {:agent-session {:prompt-mode :xml}})))
    (is (nil? (user-config/user-prompt-mode {:agent-session {:prompt-mode "lambda"}})))))

(deftest user-nucleus-prelude-override-test
  ;; Tests that nucleus prelude override accepts strings and rejects other values.
  (testing "returns string override"
    (is (= "custom prelude"
           (user-config/user-nucleus-prelude-override
            {:agent-session {:nucleus-prelude-override "custom prelude"}}))))

  (testing "returns nil for non-string override"
    (is (nil? (user-config/user-nucleus-prelude-override
               {:agent-session {:nucleus-prelude-override :custom}})))))
