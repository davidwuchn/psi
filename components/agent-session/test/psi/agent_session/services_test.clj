(ns psi.agent-session.services-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.services :as services]))

(defn- make-test-ctx []
  {:service-registry (services/create-registry)})

(deftest ensure-service-creates-and-projects-test
  (testing "ensure-service-in! creates a running subprocess service"
    (let [ctx (make-test-ctx)
          svc (services/ensure-service-in!
               ctx
               {:key [:echo "repo"]
                :type :subprocess
                :spec {:command ["bash" "-lc" "sleep 5"]
                       :cwd "/tmp"
                       :transport :stdio}
                :ext-path "/ext/test.clj"})]
      (try
        (is (= [:echo "repo"] (:key svc)))
        (is (= :running (:status svc)))
        (is (= ["bash" "-lc" "sleep 5"] (:command svc)))
        (is (= "/tmp" (:cwd svc)))
        (is (= :stdio (:transport svc)))
        (is (= "/ext/test.clj" (:ext-path svc)))
        (is (integer? (:restart-count svc)))
        (is (some? (:pid svc)))
        (is (= 1 (services/service-count-in ctx)))
        (is (= [[:echo "repo"]] (vec (services/service-keys-in ctx))))
        (is (= #{:key :status :command :cwd :transport :ext-path :pid :started-at :restart-count}
               (->> (services/project-service svc)
                    keys
                    (filter #{:key :status :command :cwd :transport :ext-path :pid :started-at :restart-count})
                    set)))
        (finally
          (services/stop-service-in! ctx [:echo "repo"]))))))

(deftest ensure-service-reuses-healthy-service-test
  (testing "ensure-service-in! returns the existing running service for the same key"
    (let [ctx  (make-test-ctx)
          svc1 (services/ensure-service-in!
                ctx
                {:key [:reuse "repo"]
                 :type :subprocess
                 :spec {:command ["bash" "-lc" "sleep 5"]
                        :cwd "/tmp"}})
          svc2 (services/ensure-service-in!
                ctx
                {:key [:reuse "repo"]
                 :type :subprocess
                 :spec {:command ["bash" "-lc" "sleep 5"]
                        :cwd "/tmp"}})]
      (try
        (is (= (:id svc1) (:id svc2)))
        (is (= 1 (services/service-count-in ctx)))
        (finally
          (services/stop-service-in! ctx [:reuse "repo"]))))))

(deftest stop-service-transitions-status-test
  (testing "stop-service-in! marks service stopped"
    (let [ctx (make-test-ctx)
          _   (services/ensure-service-in!
               ctx
               {:key [:stop "repo"]
                :type :subprocess
                :spec {:command ["bash" "-lc" "sleep 5"]
                       :cwd "/tmp"}})
          svc (services/stop-service-in! ctx [:stop "repo"])]
      (is (= :stopped (:status svc)))
      (is (some? (:stopped-at svc)))
      (is (= :stopped (:status (services/service-in ctx [:stop "repo"])))))))
