package finance.shilling.shared.data

class ServerUrlCallback(val onChange: (String) -> Unit)
class HouseholdIdCallback(val onChange: (String) -> Unit)
class ResetOnboardingCallback(val onReset: suspend () -> Unit)
class RestartHostedLoginCallback(val onRestart: suspend () -> Unit)

const val SETTINGS_KEY_SERVER_URL = "shilling_server_url"
const val DEFAULT_SERVER_URL = "https://api.shilling.finance"
const val DEFAULT_SELF_HOSTED_SERVER_URL = "http://localhost:8081"
const val SETTINGS_KEY_ONBOARDING_COMPLETED = "shilling_onboarding_completed"
const val SETTINGS_KEY_DEPLOYMENT_SELECTION = "shilling_deployment_selection"
const val SETTINGS_KEY_HOSTED_ENTRY_MODE = "shilling_hosted_entry_mode"

// Auth & identity settings keys
const val SETTINGS_KEY_DEVICE_ID = "shilling_device_id"
const val SETTINGS_KEY_LOCAL_HOUSEHOLD_ID = "shilling_local_household_id"
const val SETTINGS_KEY_AUTH_MODE = "shilling_auth_mode"
const val SETTINGS_KEY_SUPABASE_URL = "shilling_supabase_url"
const val SETTINGS_KEY_SUPABASE_ANON_KEY = "shilling_supabase_anon_key"
const val SETTINGS_KEY_AUTH_ACCESS_TOKEN = "shilling_auth_access_token"
const val SETTINGS_KEY_AUTH_REFRESH_TOKEN = "shilling_auth_refresh_token"
const val SETTINGS_KEY_AUTH_USER_ID = "shilling_auth_user_id"
const val SETTINGS_KEY_AUTH_TIER = "shilling_auth_tier"
const val SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION = "shilling_auth_pending_email_confirmation"
const val SETTINGS_KEY_AUTH_PENDING_EMAIL = "shilling_auth_pending_email"
const val SETTINGS_KEY_HOSTED_HOUSEHOLD_ID = "shilling_hosted_household_id"
const val SETTINGS_KEY_HELD_LOCAL_DATA = "shilling_held_local_data"
const val SETTINGS_KEY_PENDING_RESTORE_USER_ID = "shilling_pending_restore_user_id"
const val SETTINGS_KEY_WELCOME_NOTICE = "shilling_welcome_notice"
const val SETTINGS_KEY_TAB_ORDER = "shilling_tab_order"
