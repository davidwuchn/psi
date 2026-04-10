(ns psi.tui.patches
  (:require
   [charm.input.handler :as charm-input-handler]
   [charm.input.keymap]
   [charm.render.core]
   [charm.terminal :as charm-term])
  (:import
   [org.jline.keymap KeyMap]
   [org.jline.terminal Terminal]))

(def ^:private kitty-csi-u-pattern #"^\[(\d+);(\d+)u$")
(def ^:private modify-other-keys-pattern #"^\[27;(\d+);(\d+)~$")

(defn- kitty-mod-code->mods
  [mod-code]
  (let [c (dec mod-code)]
    {:shift (pos? (bit-and c 1))
     :alt   (or (pos? (bit-and c 2))
                (pos? (bit-and c 8))
                (pos? (bit-and c 32)))
     :ctrl  (pos? (bit-and c 4))}))

(defn- keycode->event
  [code]
  (cond
    (or (= code 13) (= code 10)) {:type :enter}
    (or (= code 127) (= code 8)) {:type :backspace}
    (<= 32 code 126)             {:type :runes :runes (str (char code))}
    :else                        nil))

(defn- parse-extended-key
  [escape-seq]
  (or
   (when-let [[_ code-s mod-s] (re-matches kitty-csi-u-pattern (or escape-seq ""))]
     (let [code (Long/parseLong code-s)
           mods (kitty-mod-code->mods (Long/parseLong mod-s))]
       (when-let [base (keycode->event code)]
         (merge base mods))))
   (when-let [[_ mod-s code-s] (re-matches modify-other-keys-pattern (or escape-seq ""))]
     (let [mods (kitty-mod-code->mods (Long/parseLong mod-s))
           code (Long/parseLong code-s)]
       (when-let [base (keycode->event code)]
         (merge base mods))))))

(defn- normalize-parsed-event
  [parsed]
  (if (and (= :runes (:type parsed))
           (:alt parsed)
           (string? (:runes parsed))
           (= 1 (count ^String (:runes parsed))))
    (let [ch (:runes parsed)]
      (cond
        (or (= ch "\r") (= ch "\n")) (-> parsed (dissoc :runes) (assoc :type :enter))
        (or (= ch "\u007f") (= ch "\u0008")) (-> parsed (dissoc :runes) (assoc :type :backspace))
        (= ch " ") (-> parsed (dissoc :runes) (assoc :type :space))
        :else parsed))
    parsed))

(defn install!
  []
  (alter-var-root
   #'charm.input.keymap/bind-from-capability!
   (constantly
    (fn [^KeyMap keymap ^Terminal terminal cap event]
      (when terminal
        (when-let [seq-val (KeyMap/key terminal cap)]
          (let [^String seq-str (if (string? seq-val)
                                  seq-val
                                  (String. ^chars seq-val))]
            (when (and (pos? (count seq-str))
                       (= (int (.charAt seq-str 0)) 27))
              (.bind keymap event (subs seq-str 1)))))))))

  (alter-var-root
   #'charm.render.core/enter-alt-screen!
   (constantly
    (fn [renderer]
      (let [terminal (:terminal @renderer)]
        (charm-term/enter-alt-screen terminal)
        (charm-term/clear-screen terminal)
        (charm-term/cursor-home terminal))
      (swap! renderer assoc :alt-screen true))))

  (alter-var-root
   #'charm.render.core/update-size!
   (constantly
    (fn [renderer width _height]
      (let [{:keys [display height old-height]}
            (assoc @renderer :old-height (:height @renderer))
            height-changed? (not= old-height height)]
        (.resize ^org.jline.utils.Display display height width)
        (swap! renderer assoc :width width :height height)
        (when height-changed?
          (.clear ^org.jline.utils.Display display)
          (let [terminal (:terminal @renderer)]
            (charm-term/clear-screen terminal)
            (charm-term/cursor-home terminal)))))))

  (alter-var-root
   #'charm-input-handler/parse-input
   (fn [orig]
     (fn
       ([byte-val]
        (normalize-parsed-event (orig byte-val)))
       ([byte-val escape-seq]
        (normalize-parsed-event (orig byte-val escape-seq)))
       ([byte-val escape-seq keymap]
        (let [parsed (orig byte-val escape-seq keymap)
              parsed (if (and (= :unknown (:type parsed))
                              (string? escape-seq))
                       (or (parse-extended-key escape-seq)
                           parsed)
                       parsed)]
          (normalize-parsed-event parsed))))))
  nil)
