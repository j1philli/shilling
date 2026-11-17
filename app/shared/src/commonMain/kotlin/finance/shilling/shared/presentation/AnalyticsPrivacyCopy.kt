package finance.shilling.shared.presentation

/** Shared disclosure shown before opting in, including self-hosted onboarding. */
internal const val ANALYTICS_PRIVACY_NOTICE =
    "Optional: send onboarding, budget account, schedule, transaction, CSV import, and receipt attachment events " +
    "to PostHog in the US to help improve Shilling. Events use a random installation ID; PostHog also receives " +
    "your IP address and derives approximate location. No amounts, names, filenames, receipt or CSV contents, " +
    "household IDs, or error text are sent. No session recording. You can turn sharing off in Settings to stop " +
    "future events; this does not delete events already sent. This also applies when using your own server."
