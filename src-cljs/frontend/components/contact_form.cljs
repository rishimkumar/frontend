(ns frontend.components.contact-form
  (:require [clojure.string :as str]
            [frontend.utils.ajax :as ajax]
            [goog.style :as gstyle]
            [om.core :as om :include-macros true])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [frontend.utils :refer [html]])
  (:import [goog.math Rect]))

(defn- update-height
  "Calculates a bounding box for the rendered children of container, then sets
  the height of container to encompass those children."
  [container]
  (let [rendered-children (filter #(not= "none" (gstyle/getComputedStyle % "display")) (.-children container))
        child-client-rects (map #(.getBoundingClientRect %) rendered-children)
        child-goog-rects (map #(Rect. (.-left %) (.-top %) (.-width %) (.-height %)) child-client-rects)
        container-client-rect (.getBoundingClientRect container)
        origin-goog-rect (Rect. (.-left container-client-rect) (.-top container-client-rect) 0 0)
        bounding-rect (reduce Rect.boundingRect origin-goog-rect child-goog-rects)]
    (gstyle/setHeight container (.-height bounding-rect))))

(defn transitionable-height
  "A div whose height will be set explicitly to the height of its contents,
  emulating height: auto. This allows CSS transitions to work correctly with
  the height property.

  Accepts a class name as :class and a child or list of children as :children.

  To animate the height, apply a transition property. You'll also want to hide
  the overflow. For example:

  .my-transitionable-height-element {
    overflow: hidden;
    transition: height 0.5s;
  }"
  [props owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (update-height (om/get-node owner)))
    om/IDidUpdate
    (did-update [_ _ _]
      (update-height (om/get-node owner)))
    om/IRender
    (render [_]
      (html
        [:div
         {:class (:class props)}
         (:children props)]))))


(defn- validation-message [control]
  (let [validity (.-validity control)]
    (cond
      (.-valid validity) nil
      (.-valueMissing validity) "Please fill out this field."

      (.-typeMismatch validity)
      (case (.-type control)
        "email" "Please enter a valid email address.")

      ;; Default to the message provided by the browser.
      :else (.-validationMessage control))))

(defn- form-control [props owner]
  (let [update-state
        (fn [control]
          (om/set-state! owner {:value (.-value control)
                                :validation-message (validation-message control)}))]
    (reify
      om/IInitState
      (init-state [_]
        {:value nil
         :validation-message nil})
      om/IDidMount
      (did-mount [_]
        ;; Update our state based on the DOM immediately (and later on-change).
        (update-state (om/get-node owner "control")))
      om/IRenderState
      (render-state [_ {:keys [value validation-message]}]
        (html
          [:div.validated-form-control
           [(:constructor props)
            (merge (dissoc props :constructor :show-validations?)
                   {:value value
                    :ref "control"
                    :on-change #(update-state (.-target %))})]
           (om/build transitionable-height
                     {:class "validation-message-container"
                      :children (html
                                  (when validation-message
                                    [:div.validation-message validation-message]))})])))))

(defn contact-form
  "Returns a function which reifys an Om component (that is, returns something
  you'd pass to om.core/build). props is a map of attributes to give to the
  form. children-f is a function which takes three arguments:

  - A function to create form controls.
  - A notice to display to the user, such as an error (or nil if there's no message).
  - A boolean indicating whether the form is waiting for a response from the server.

  Optionally, if a :params-filter is given, it will be called when the form is
  submitted with a map of the params the form would normally submit. It should
  return a map of params for the form to actually submit. Both maps should have
  string keys and values."
  ([props children-f] (contact-form props {} children-f))
  ([props {:keys [params-filter] :or {params-filter identity}} children-f]
   (fn [_ owner]
     (reify
       om/IInitState
       (init-state [_]
         {:show-validations? false
          :notice nil
          :loading? false})
       om/IRenderState
       (render-state [_ {:keys [show-validations? notice loading?]}]
         (html
           [:form
            (merge
              props
              {:class (str/join " " (filter identity ["contact"
                                                      (:class props)
                                                      (when show-validations? "show-validations")]))
               :no-validate true
               :on-submit (fn [e]
                            (.preventDefault e)
                            (let [form (.-target e)
                                  action (.-action form)
                                  ;; NOTE: HTMLFormElement.elements returns the form's *listed* elements; we really want
                                  ;; the *submittable* elements. Here, we assume they're the same.
                                  ;; https://html.spec.whatwg.org/multipage/forms.html#categories
                                  params (into {} (map (juxt #(.-name %) #(.-value %)) (.-elements form)))
                                  filtered-params (params-filter params)
                                  valid? (fn [f] (every? #(.checkValidity %) (.-elements f)))]
                              (if (not (valid? form))
                                (om/set-state! owner [:show-validations?] true)
                                (do
                                  (om/set-state! owner [:show-validations?] false)
                                  (om/set-state! owner [:loading?] true)
                                  (go (let [resp (<! (ajax/managed-form-post
                                                       action
                                                       :params filtered-params))]
                                        (om/set-state! owner [:loading?] false)
                                        (if (= (:status resp) :success)
                                          (do
                                            (om/set-state! owner [:notice] nil)
                                            (.reset form))
                                          (om/set-state! owner [:notice] {:type "error" :message "Sorry! There was an error sending your message."}))))))))})

            (let [control
                  (fn [constructor props]
                    (om/build form-control
                              (merge
                                {:constructor constructor
                                 :show-validations? show-validations?}
                                props)))]
              (children-f control notice loading?))]))))))
