(ns hugoduncan.psi
  "bbin entry point — delegates to psi.launcher-main.
   bbin derives the main namespace from Maven coords (org.hugoduncan/psi)
   and calls hugoduncan.psi/-main, so this shim bridges the gap."
  (:require [psi.launcher-main :as launcher-main]))

(defn -main [& args]
  (apply launcher-main/-main args))
