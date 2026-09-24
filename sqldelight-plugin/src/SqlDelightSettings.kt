package finance.shilling.build

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface SqlDelightSettings {
    val packageName: String get() = "finance.shilling.shared.db"
    val databaseName: String get() = "ShillingDatabase"
}
