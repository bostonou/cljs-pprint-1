;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.pprint
  (:require-macros
    [clojure.pprint :refer [with-pretty-writer getf setf]])
  (:require
    [cljs.core :refer [IWriter IDeref]]
    [clojure.string :as string]
    ))

;;======================================================================
;; Vars
;;======================================================================

;; referenced cljs.core vars:
;;   *print-length*
;;   *print-level*

;; referenced vars not found in cljs:
;;   *out*
;;   *print-base*
;;   *print-radix*

;; pprint vars:
;;   *print-pretty* true
;;   *print-pprint-dispatch* nil
;;   *print-right-margin* 72
;;   *print-miser-width* 40

(def ^:dynamic
 ^{:doc "Pretty printing will try to avoid anything going beyond this column.
Set it to nil to have pprint let the line be arbitrarily long. This will ignore all
non-mandatory newlines.",
   :added "1.2"}
 *print-right-margin* 72)

(def ^:dynamic
 ^{:doc "The column at which to enter miser style. Depending on the dispatch table,
miser style add newlines in more places to try to keep lines short allowing for further
levels of nesting.",
   :added "1.2"}
 *print-miser-width* 40)

(def ^:dynamic
 ^{:doc "Bind to true if you want write to use pretty printing"}
 *print-pretty* true)

;;======================================================================
;; Protocols
;;======================================================================

;; referenced Interfaces:
;;  definterface PrettyFlush

(defprotocol IPrettyFlush
  (-ppflush [pp]))

;;======================================================================
;; Column Writer
;;======================================================================

(def ^:dynamic ^{:private true} *default-page-width* 72)

(defn- get-field [this sym]
  (sym @@this))

(defn- set-field [this sym new-val]
  (swap! @this assoc sym new-val))

(defn- get-column [this]
  (get-field this :cur))

(defn- get-line [this]
  (get-field this :line))

(defn- get-max-column [this]
  (get-field this :max))

(defn- set-max-column [this new-max]
  (set-field this :max new-max)
  nil)

(defn- get-writer [this]
  (get-field this :base))

;; Why is the c argument an integer?
(defn- c-write-char [this c]
  (if (= c \newline)
    (do
      (set-field this :cur 0)
      (set-field this :line (inc (get-field this :line))))
    (set-field this :cur (inc (get-field this :cur))))
  (-write (get-field this :base) c))

(defn- column-writer
  ([writer] (column-writer writer *default-page-width*))
  ([writer max-columns]
     (let [fields (atom {:max max-columns, :cur 0, :line 0 :base writer})]
       (reify

         IDeref
         (-deref [_] fields)

         IWriter
         (-flush [_]
           (-flush writer))
         (-write
           ;;-write isn't multi-arity, so need different way to do this
           #_([this ^chars cbuf ^Number off ^Number len]
            (let [writer (get-field this :base)]
              (-write writer cbuf off len)))
           [this x]
           (condp = (type x)
             js/String
             (let [s x
                   nl (.lastIndexOf s \newline)]
               (if (neg? nl)
                 (set-field this :cur (+ (get-field this :cur) (count s)))
                 (do
                   (set-field this :cur (- (count s) nl 1))
                   (set-field this :line (+ (get-field this :line)
                                            (count (filter #(= % \newline) s))))))
               (-write ^Writer (get-field this :base) s))
             js/Number
             (c-write-char this x)))))))

;;======================================================================
;; Main Writer
;;======================================================================

;;----------------------------------------------------------------------
;; Forward declarations
;;----------------------------------------------------------------------

(declare get-miser-width)

;;----------------------------------------------------------------------
;; DATA STRUCTURES
;;
;X  defstruct logical-block :parent :section :start-col :indent
;;                          :done-nl :intra-block-nl
;;                          :prefix :per-line-prefix :suffix
;;                          :logical-block-callback
;;
;;  defn- ancestor?
;;  defn- buffer-length
;;
;;  defstruct section       :parent
;;  deftype buffer-blob     :data :trailing-white-space :start-pos :end-pos
;;  deftype nl-t            :type :logical-block :start-pos :end-pos
;;  deftype start-block-t   :logical-block :start-pos :end-pos
;;  deftype end-block-t     :logical-block :start-pos :end-pos
;;  deftype indent-t        :logical-block :relative-to :offset :start-pos :end-pos


(defrecord ^{:private true} logical-block
  [parent section start-col indent
   done-nl intra-block-nl
   prefix per-line-prefix suffix
   logical-block-callback])

(defn- ancestor? [parent child]
  (loop [child (:parent child)]
    (cond
      (nil? child) false
      (identical? parent child) true
      :else (recur (:parent child)))))

(defn- buffer-length [l]
  (let [l (seq l)]
    (if l
      (- (:end-pos (last l)) (:start-pos (first l)))
      0)))

;;----------------------------------------------------------------------
;; TOKEN WRITERS
;;
;; defmulti write-token
;;    :start-block-t
;;    :end-block-t
;;    :indent-t
;;    :buffer-blob
;;    :nl-t
;; defn- write-tokens

(def ^:private pp-newline (fn [] "\n"))

(declare emit-nl)

(defmulti ^{:private true} write-token #(:type-tag %2))

(defmethod write-token :start-block-t [this token]
  (when-let [cb (getf :logical-block-callback)] (cb :start))
  (let [lb (:logical-block token)]
    (when-let [prefix (:prefix lb)]
      (-write (getf :base) prefix))
    (let [col (get-column (getf :base))]
      (reset! (:start-col lb) col)
      (reset! (:indent lb) col))))

(defmethod write-token :end-block-t [this token]
  (when-let [cb (getf :logical-block-callback)] (cb :end))
  (when-let [suffix (:suffix (:logical-block token))]
    (-write (getf :base) suffix)))

(defmethod write-token :indent-t [this token]
  (let [lb (:logical-block token)]
    (reset! (:indent lb)
            (+ (:offset token)
               (condp = (:relative-to token)
                 :block @(:start-col lb)
                 :current (get-column (getf :base)))))))

(defmethod write-token :buffer-blob [this token]
  (-write (getf :base) (:data token)))

(defmethod write-token :nl-t [this token]
  (if (or (= (:type token) :mandatory)
          (and (not (= (:type token) :fill))
               @(:done-nl (:logical-block token))))
    (emit-nl this token)
    (if-let [tws (getf :trailing-white-space)]
      (-write (getf :base) tws)))
  (setf :trailing-white-space nil))

(defn- write-tokens [this tokens force-trailing-whitespace]
  (doseq [token tokens]
    (if-not (= (:type-tag token) :nl-t)
      (if-let [tws (getf :trailing-white-space)]
        (-write (getf :base) tws)))
    (write-token this token)
    (setf :trailing-white-space (:trailing-white-space token))
    (let [tws (getf :trailing-white-space)]
      (when (and force-trailing-whitespace tws)
        (-write (getf :base) tws)
        (setf :trailing-white-space nil)))))

;;----------------------------------------------------------------------
;; EMIT NEWLINE? FUNCTIONS
;;
;; defn- tokens-fit?
;; defn- linear-nl?
;; defn- miser-nl?
;;
;; defmulti emit-nl?
;;    :linear
;;    :miser
;;    :fill
;;    :mandatory

;;----------------------------------------------------------------------
;; emit-nl? method defs for each type of new line. This makes
;; the decision about whether to print this type of new line.
;;----------------------------------------------------------------------

(defn- tokens-fit? [this tokens]
  (let [maxcol (get-max-column (getf :base))]
    (or
      (nil? maxcol)
      (< (+ (get-column (getf :base)) (buffer-length tokens)) maxcol))))

(defn- linear-nl? [this lb section]
  (or @(:done-nl lb)
      (not (tokens-fit? this section))))

(defn- miser-nl? [this lb section]
  (let [miser-width (get-miser-width this)
        maxcol (get-max-column (getf :base))]
    (and miser-width maxcol
         (>= @(:start-col lb) (- maxcol miser-width))
         (linear-nl? this lb section))))

(defmulti ^{:private true} emit-nl? (fn [t _ _ _] (:type t)))

(defmethod emit-nl? :linear [newl this section _]
  (let [lb (:logical-block newl)]
    (linear-nl? this lb section)))

(defmethod emit-nl? :miser [newl this section _]
  (let [lb (:logical-block newl)]
    (miser-nl? this lb section)))

(defmethod emit-nl? :fill [newl this section subsection]
  (let [lb (:logical-block newl)]
    (or @(:intra-block-nl lb)
        (not (tokens-fit? this subsection))
        (miser-nl? this lb section))))

(defmethod emit-nl? :mandatory [_ _ _ _]
  true)

;;----------------------------------------------------------------------
;; VARIOUS SUPPORT FUNCTIONS
;;
;; defn- get-section
;; defn- get-sub-section
;;
;; defn- update-nl-state
;; defn- emit-nl
;; defn- split-at-newline
;;
;; defmulti tok
;;    :nl-t
;;    :buffer-blob
;;    :default
;; defn- toks
;;
;; defn- write-token-string
;; defn- write-line
;;
;; defn- add-to-buffer
;; defn- write-buffered-output
;; defn- write-white-space
;; defn- write-initial-lines
;; defn- p-write-char

(defn- get-section [buffer]
  (let [nl (first buffer)
        lb (:logical-block nl)
        section (seq (take-while #(not (and (nl-t? %) (ancestor? (:logical-block %) lb)))
                                 (next buffer)))]
    [section (seq (drop (inc (count section)) buffer))]))

(defn- get-sub-section [buffer]
  (let [nl (first buffer)
        lb (:logical-block nl)
        section (seq (take-while #(let [nl-lb (:logical-block %)]
                                   (not (and (nl-t? %) (or (= nl-lb lb) (ancestor? nl-lb lb)))))
                                 (next buffer)))]
    section))

(defn- update-nl-state [lb]
  (reset! (:intra-block-nl lb) true)
  (reset! (:done-nl lb) true)
  (loop [lb (:parent lb)]
    (if lb
      (do (reset! (:done-nl lb) true)
          (reset! (:intra-block-nl lb) true)
          (recur (:parent lb))))))

(defn- emit-nl [this nl]
  (-write (getf :base) (pp-newline))
  (setf :trailing-white-space nil)
  (let [lb (:logical-block nl)
        prefix (:per-line-prefix lb)]
    (if prefix
      (-write (getf :base) prefix))
    (let [istr (apply str (repeat (- @(:indent lb) (count prefix)) \space))]
      (-write (getf :base) istr))
    (update-nl-state lb)))

(defn- split-at-newline [tokens]
  (let [pre (seq (take-while #(not (nl-t? %)) tokens))]
    [pre (seq (drop (count pre) tokens))]))

;;; If there are newlines in the string, print the lines up until the last newline,
;;; making the appropriate adjustments. Return the remainder of the string
(defn- write-initial-lines
  [^Writer this ^String s]
  (let [lines (string/split s "\n" -1)]
    (if (= (count lines) 1)
      s
      (let [^String prefix (:per-line-prefix (first (getf :logical-blocks)))
            ^String l (first lines)]
        (if (= :buffering (getf :mode))
          (let [oldpos (getf :pos)
                newpos (+ oldpos (count l))]
            (setf :pos newpos)
            (add-to-buffer this (make-buffer-blob l nil oldpos newpos))
            (write-buffered-output this))
          (do
            (write-white-space this)
            (-write (getf :base) l)))
        (-write (getf :base) \newline)
        (doseq [^String l (next (butlast lines))]
          (-write (getf :base) l)
          (-write (getf :base) (pp-newline))
          (if prefix
            (-write (getf :base) prefix)))
        (setf :buffering :writing)
        (last lines)))))

;;----------------------------------------------------------------------
;; CONSTRUCTOR
;;
;X defn- pretty-writer

(defn- pretty-writer [writer max-columns miser-width]
  (let [lb (logical-block. nil nil (atom 0) (atom 0) (atom false) (atom false))
        ; NOTE: may want to just `specify!` #js { ... fields ... } with the protocols
        fields (atom {:pretty-writer true
                      :base (column-writer writer max-columns)
                      :logical-blocks lb
                      :sections nil
                      :mode :writing
                      :buffer []
                      :buffer-block lb
                      :buffer-level 1
                      :miser-width miser-width
                      :trailing-white-space nil
                      :pos 0})]
    (reify

      IDeref
      (-deref [_] fields)

      IWriter
      (-write [this x]
        ;;     (prlabel write x (getf :mode))
        (condp = (type x)
          js/String
          (let [s0 (write-initial-lines this x)
                s (string/replace-first s0 #"\s+$" "")
                white-space (string/subs s0 (count s))
                mode (getf :mode)]
            (if (= mode :writing)
              (do
                (write-white-space this)
                (-write (getf :base) s)
                (setf :trailing-white-space white-space))
              (let [oldpos (getf :pos)
                    newpos (+ oldpos (count s0))]
                (setf :pos newpos)
                (add-to-buffer this (make-buffer-blob s white-space oldpos newpos)))))
          js/Number
          (p-write-char this x)))
      (-flush [this]
        (-ppflush this)
        (-flush (getf :base)))

      IPrettyFlush
      (-ppflush [this]
        (if (= (getf :mode) :buffering)
          (do
            (write-tokens this (getf :buffer) true)
            (setf :buffer []))
          (write-white-space this)))

      )))


;;----------------------------------------------------------------------
;; METHODS
;;
;; defn start-block
;; defn end-block
;; defn- nl
;; defn- indent
;; defn- get-miser-width
;; defn- set-miser-width
;; defn- set-logical-block-callback


(defn- get-miser-width [this]
  (getf :miser-width))

;;======================================================================
;; Simple Dispatch
;;======================================================================

;; defn- pprint-simple-list
;; defn- pprint-list
;; defn- pprint-vector
;; defn- pprint-array
;; defn- pprint-map
;; defn- pprint-set
;; defn- pprint-pqueue
;; defn- pprint-ideref
;; defn- pprint-simple-default
;;
;; defmulti simple-dispatch


;;======================================================================
;; Helpers
;;======================================================================

;; pprint-logical-block
;; pprint-newline
;; pprint-length-loop

;;======================================================================
;; Main Interface
;;======================================================================

;; defn write-out
;X defn pprint
;; defn pp

(defn- pretty-writer?
  "Return true iff x is a PrettyWriter"
  [x] (and (instance? IDeref x) (:pretty-writer @@x)))

(defn- make-pretty-writer
  "Wrap base-writer in a PrettyWriter with the specified right-margin and miser-width"
  [base-writer right-margin miser-width]
  (pretty-writer base-writer right-margin miser-width))

(defn pprint*
  "Pretty-print to an IWriter instance."
  ([object] (throw (js/Error. (str "Cannot default pprint* writer to *out* (not yet implemented)"))))
  ([object writer]
     (with-pretty-writer writer
       (binding [*print-pretty* true]
         (write-out object)))))

(defn pprint-sb
  "Get pretty-printed string buffer."
  [object]
  (let [sb (StringBuffer.)
        writer (StringBufferWriter. sb)]
    (pprint* object writer)
    (-flush writer)
    sb))

(defn pprint-str
  "Get pretty-printed string."
  [object]
  (str (pprint-sb object)))

(defn pprint
  "Pretty-print to the *print-fn*."
  [object]
  (*print-fn* (pprint-str object)))

