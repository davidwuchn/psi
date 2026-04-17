(ns hugoduncan.bb-task-lib.gordian
  "Run gordian codebase-architecture analysis, auto-installing via bbin if needed.

  Usage: bb gordian [command] [paths...] [options]

  All arguments are passed directly to gordian. Run 'bb gordian --help' to see
  gordian's full documentation.

  If gordian is not on PATH it is installed automatically via bbin
  (bbin must already be installed and configured).

  Commands (default: analyze):
    analyze      Raw metrics table + optional coupling sections
    diagnose     Ranked findings with severity levels
    compare      Compare two saved EDN snapshots (before.edn after.edn)
    gate         CI gate — compare current codebase against a saved baseline
    subgraph     Family/subsystem view for a namespace prefix
    communities  Discover latent architecture communities
    dsm          Dependency Structure Matrix view
    tests        Analyze test architecture and test-vs-source coupling
    explain      Everything gordian knows about a namespace
    explain-pair Everything gordian knows about a pair of namespaces

  Commonly used options:
    --edn                   Machine-readable EDN output
    --json                  Machine-readable JSON output
    --markdown              Markdown output
    --html-file <file>      Write self-contained HTML report to <file>
    --include-tests         Include test directories in auto-discovery
    --exclude <regex>       Exclude namespaces matching regex (repeatable)
    --baseline <file>       Baseline EDN snapshot for the gate command
    --max-pc-delta <float>  Gate: max allowed propagation-cost increase
    --fail-on <csv>         Gate: comma-separated strict checks
    --change                Enable change-coupling analysis (uses git log)
    --change-since <date>   Limit change coupling to commits after <date>
    --conceptual <float>    Conceptual coupling at given similarity threshold
    --rank <mode>           Diagnose ranking: severity | actionability
    --lens <mode>           Communities lens: structural | conceptual | change | combined
    --help                  Show gordian's own help message"
  (:require [babashka.fs      :as fs]
            [babashka.process :as proc]
            [clojure.string   :as str]))

(def ^:private bbin-coord "io.github.hugoduncan/gordian")

(defn- install-gordian! []
  (println "gordian not found on PATH; installing via bbin…")
  (let [{:keys [exit]} (proc/shell {:continue true} "bbin" "install" bbin-coord)]
    (when-not (zero? exit)
      (throw (ex-info "Failed to install gordian via bbin"
                      {:coord bbin-coord})))))

(defn- gordian-exe
  "Return the path to the gordian executable, installing it via bbin if absent.
  Falls back to resolving through `bbin bin` after a fresh install because
  PATH is fixed at process start and fs/which may not see the new binary."
  []
  (or (some-> (fs/which "gordian") str)
      (do
        (install-gordian!)
        ;; Resolve via bbin bin dir in case PATH hasn't been re-evaluated.
        (let [bbin-bin  (str/trim (:out (proc/sh "bbin" "bin")))
              candidate (str (fs/file bbin-bin "gordian"))]
          (if (fs/exists? candidate)
            candidate
            (throw (ex-info "gordian not found after bbin install"
                            {:bbin-bin bbin-bin :coord bbin-coord})))))))

(defn run []
  (let [exe  (gordian-exe)
        argv (into [exe] *command-line-args*)]
    ;; exec replaces the current process — gordian's exit code becomes our exit code.
    ;; This is intentional: the gate command exits non-zero on regressions, and
    ;; callers (CI, bb tasks) must see that signal.
    (proc/exec argv)))
