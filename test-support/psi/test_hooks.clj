(ns psi.test-hooks
  "Kaocha hooks for test-suite-wide setup."
  (:require [taoensso.timbre :as timbre]))

(defn pre-run
  "Set log level to :info before the test run."
  [test-plan]
  (timbre/set-min-level! :info)
  test-plan)
