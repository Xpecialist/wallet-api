/** Labelled text input. Extra props are passed to the <input>. */
export function TextField({ label, ...props }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input {...props} />
    </label>
  );
}

/** Labelled select. `options` is a list of `[value, label]` pairs. */
export function SelectField({ label, options, ...props }) {
  return (
    <label className="field">
      <span>{label}</span>
      <select {...props}>
        {options.map(([value, text]) => (
          <option key={value} value={value}>
            {text}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Form wrapper with a submit button that is disabled while `busy`. */
export function Form({ onSubmit, busy, submitText, submitClass, children }) {
  return (
    <form
      className="form"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(e);
      }}
    >
      {children}
      <button type="submit" className={submitClass} disabled={busy} aria-busy={busy ? 'true' : undefined}>
        {busy ? 'Please wait...' : submitText}
      </button>
    </form>
  );
}
