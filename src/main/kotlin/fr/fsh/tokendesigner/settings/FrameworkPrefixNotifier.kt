package fr.fsh.tokendesigner.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fires once per project: when the plugin opens a project that depends on a
 * known UI framework (PrimeNG, Ionic, Angular Material, …) but no
 * `externalPrefixes` are configured yet on any scope, shows a balloon offering
 * to add the detected prefixes in one click. Persists a "notified" flag in
 * settings so the user is never nagged twice — even if they dismiss without
 * acting.
 *
 * Detection is delegated to [FrameworkPrefixDetector]; this class only
 * orchestrates UI and writes back into settings.
 */
class FrameworkPrefixNotifier : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = TokenSelectorSettings.getInstance(project)
        if (settings.frameworkPrefixesNotified) return
        // If the user already configured prefixes (manually or via an Import),
        // they know what they're doing — silent.
        if (settings.scopes.any { it.externalPrefixes.isNotEmpty() }) {
            settings.frameworkPrefixesNotified = true
            return
        }

        // Heavy file walks must run off the EDT.
        val detections = withContext(Dispatchers.IO) {
            FrameworkPrefixDetector.detect(project)
        }
        if (detections.isEmpty()) return

        val frameworks = detections.joinToString(", ") { it.framework.displayName }
        val prefixes = detections.joinToString(", ") { it.framework.prefix }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Token Flow — external CSS variables detected",
                "Found <b>$frameworks</b> in your project. " +
                    "Want Token Flow to stop flagging <code>$prefixes</code> references " +
                    "as broken? Adding them as <i>external prefixes</i> on every scope " +
                    "takes one click.",
                NotificationType.INFORMATION,
            )

        notification.addAction(object : NotificationAction("Add to all scopes") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                applyDetectionsToAllScopes(project, detections)
                settings.frameworkPrefixesNotified = true
                n.expire()
            }
        })
        notification.addAction(object : NotificationAction("Configure…") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                settings.frameworkPrefixesNotified = true
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Token Flow")
                n.expire()
            }
        })
        notification.addAction(object : NotificationAction("Don't show again") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                settings.frameworkPrefixesNotified = true
                n.expire()
            }
        })

        ApplicationManager.getApplication().invokeLater {
            notification.notify(project)
        }
    }

    /**
     * Adds every detected prefix to every configured scope's `externalPrefixes`
     * (de-duplicated). When no scope exists yet, falls back to creating an
     * implicit *Common* scope so the prefix is at least stored somewhere
     * persistent — otherwise the user's one-click acceptance would silently
     * lose its effect.
     */
    private fun applyDetectionsToAllScopes(
        project: Project,
        detections: List<FrameworkPrefixDetector.Detection>,
    ) {
        val settings = TokenSelectorSettings.getInstance(project)
        val prefixes = detections.map { it.framework.prefix }.distinct()
        val scopes = settings.scopes.toMutableList()
        if (scopes.isEmpty()) {
            scopes += Scope(
                name = "Common",
                rootPath = "",
                sourcePaths = emptyList(),
                externalPrefixes = prefixes,
            )
        } else {
            for (i in scopes.indices) {
                val s = scopes[i]
                val merged = (s.externalPrefixes + prefixes).distinct()
                if (merged.size != s.externalPrefixes.size) {
                    scopes[i] = s.copy(externalPrefixes = merged)
                }
            }
        }
        settings.scopes = scopes
        settings.fireScopesChanged()
    }

    private companion object {
        const val NOTIFICATION_GROUP = "DesignTokenSelector"
    }
}
