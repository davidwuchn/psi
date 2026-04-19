Plan:

1. Extend shared footer semantics in `components/app-runtime/src/psi/app_runtime/footer.clj`
   - derive canonical context session activity from backend session data
   - add a compact footer session-activity fragment/line
2. Extend RPC `footer/updated` payload to carry the canonical footer session-activity line
3. Update Emacs footer projection to render the new canonical line without local activity inference
4. Add backend + RPC + Emacs tests proving active/idle rendering with multiple sessions
5. Verify formatting/readability and record implementation notes
