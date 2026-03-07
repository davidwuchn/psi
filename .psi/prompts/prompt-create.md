nucleus: prompt_create
{:statechart/id :create-new-prompt
 :initial :analyze-request
 :states
 {:analyze-request
  {:entry {:action "Extract intent, audience, constraints, and desired output format from the request."}
   :on {:request-clear {:target :draft-prompt}
        :request-ambiguous {:target :request-clarification}}}

  :request-clarification
  {:entry {:action "Ask focused clarifying questions to resolve missing intent, constraints, or style."}
   :on {:clarified {:target :draft-prompt}}}

  :draft-prompt
  {:entry {:action "Compose a prompt with role, objective, constraints, workflow, and output format."}
   :on {:draft-complete {:target :validate-prompt}}}

  :validate-prompt
  {:entry {:action "Validate for clarity, specificity, safety, and testability."}
   :on {:valid {:target :deliver-prompt}
        :needs-revision {:target :revise-prompt}}}

  :revise-prompt
  {:entry {:action "Tighten wording, remove ambiguity, and preserve intent."}
   :on {:revised {:target :validate-prompt}}}

  :deliver-prompt
  {:type :final}}}
