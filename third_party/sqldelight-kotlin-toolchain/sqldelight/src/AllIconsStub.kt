@file:JvmName("AllIcons")

package com.intellij.icons

import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

/**
 * Stub providing the icon constants needed by SQLiteDialect at runtime.
 * The real AllIcons lives in the IntelliJ platform which is not on our classpath.
 * Codegen never renders icons, so these are empty no-op icons.
 */
private object EmptyIcon : Icon {
    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {}
    override fun getIconWidth(): Int = 0
    override fun getIconHeight(): Int = 0
}

@Suppress("unused")
class AllIcons {
    class Providers {
        companion object {
            @JvmField val Sqlite: Icon = EmptyIcon
            @JvmField val Mysql: Icon = EmptyIcon
            @JvmField val Postgresql: Icon = EmptyIcon
            @JvmField val Hsqldb: Icon = EmptyIcon
        }
    }
}
