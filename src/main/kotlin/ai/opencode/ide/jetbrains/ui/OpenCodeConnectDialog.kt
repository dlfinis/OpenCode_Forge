package ai.opencode.ide.jetbrains.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.util.Base64
import javax.swing.JComponent
import javax.swing.JPanel

internal data class ServerAddress(val hostname: String, val port: Int, val scheme: String = "http") {
    val isLocal: Boolean
        get() = hostname in LOCAL_HOSTS

    val isSecure: Boolean
        get() = scheme == "https"

    fun asAddress(): String = if (isSecure) "https://$hostname:$port" else "$hostname:$port"

    private companion object {
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0")
    }
}

internal fun chooseConnectionAddress(
    rememberedAddress: ServerAddress?,
    discoveredLocalPort: Int?
): String? = when {
    rememberedAddress != null && !rememberedAddress.isLocal -> rememberedAddress.asAddress()
    rememberedAddress != null && discoveredLocalPort == rememberedAddress.port -> rememberedAddress.asAddress()
    discoveredLocalPort != null -> "127.0.0.1:$discoveredLocalPort"
    rememberedAddress != null -> rememberedAddress.asAddress()
    else -> null
}

/**
 * Dialog for configuring OpenCode server connection.
 * Allows user to specify host:port before starting the terminal.
 */
class OpenCodeConnectDialog(
    private val project: Project,
    defaultAddress: String
) : DialogWrapper(project, true) {

    private val initialAddress = requireNotNull(parseAddress(defaultAddress)) {
        "Default OpenCode address must use hostname:port format"
    }

    data class ConnectionInfo(
        val hostname: String,
        val port: Int,
        val password: String?,
        val useWebInterface: Boolean,
        val customBasePath: String? = null,
        val scheme: String = "http"
    )

    private val addressField = JBTextField(initialAddress.asAddress())
    private val passwordField = JBPasswordField()
    private val basePathField = ComboBox<String>().apply {
        isEditable = true
    }
    
    var hostname: String = initialAddress.hostname
        private set
    var port: Int = initialAddress.port
        private set
    var scheme: String = initialAddress.scheme
        private set
    var password: String? = null
        private set
    var customBasePath: String? = null
        private set
    var useWebInterface: Boolean = false
        private set

    init {
        title = "Connect to OpenCode"
        setOKButtonText("Connect")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(180))

        val formPanel = JPanel(GridLayout(6, 1, 0, JBUI.scale(4)))
        val addressLabel = JBLabel("Server address:")
        val passwordLabel = JBLabel("Server password (optional):")
        val basePathLabel = JBLabel("Custom base path (optional):")

        // Load saved values
        val props = PropertiesComponent.getInstance()
        // Load saved password (Base64 encoded for basic obfuscation)
        try {
            val encodedPassword = props.getValue(PROP_LAST_PASSWORD, "")
            if (encodedPassword.isNotBlank()) {
                val decodedPassword = String(Base64.getDecoder().decode(encodedPassword))
                passwordField.text = decodedPassword
            }
        } catch (e: Exception) {
            // Ignore if password retrieval fails
        }

        // Load saved custom base path
        val savedPath = props.getValue(PROP_CUSTOM_BASE_PATH, "")

        // Populate module paths
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules
        val paths = modules.mapNotNull { it.guessModuleDir()?.path }.sorted()
        paths.forEach { basePathField.addItem(it) }

        if (savedPath.isNotBlank()) {
            basePathField.item = savedPath
        } else {
            basePathField.selectedIndex = -1
        }

        addressField.toolTipText = "Format: hostname:port (e.g., 127.0.0.1:4096). Use https://host:port for TLS."
        passwordField.toolTipText = "OPENCODE_SERVER_PASSWORD"
        passwordField.emptyText.text = "For remote OpenCode servers"
        basePathField.toolTipText = "Override project base path for opencode.exe working directory"

        formPanel.add(addressLabel)
        formPanel.add(addressField)
        formPanel.add(passwordLabel)
        formPanel.add(passwordField)
        formPanel.add(basePathLabel)
        formPanel.add(basePathField)

        panel.add(formPanel, BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = addressField

    override fun doValidate(): ValidationInfo? {
        val input = addressField.text.trim()
        
        if (input.isBlank()) {
            return ValidationInfo("Server address cannot be empty", addressField)
        }
        
        if (parseAddress(input) == null) {
            return ValidationInfo("Invalid format. Expected: hostname:port or https://hostname:port", addressField)
        }

        return null
    }

    override fun doOKAction() {
        val parsed = requireNotNull(parseAddress(addressField.text)) { "Address validated by doValidate" }
        hostname = parsed.hostname
        port = parsed.port
        scheme = parsed.scheme

        val passwordValue = passwordField.password.concatToString().trim()
        password = passwordValue.ifBlank { null }
        
        val basePathValue = (basePathField.editor.item as? String)?.trim() ?: ""
        customBasePath = basePathValue.ifBlank { null }

        useWebInterface = false

        // Save values for next time
        val props = PropertiesComponent.getInstance()
        props.setValue(PROP_LAST_ADDRESS, parsed.asAddress())
        
        // Save password (Base64 encoded for basic obfuscation)
        try {
            if (!password.isNullOrBlank()) {
                val encodedPassword = Base64.getEncoder().encodeToString(password!!.toByteArray())
                props.setValue(PROP_LAST_PASSWORD, encodedPassword)
            } else {
                // Clear saved password if empty
                props.unsetValue(PROP_LAST_PASSWORD)
            }
        } catch (e: Exception) {
            // Ignore if password save fails
        }

        // Save custom base path
        if (!customBasePath.isNullOrBlank()) {
            props.setValue(PROP_CUSTOM_BASE_PATH, customBasePath!!)
        } else {
            props.unsetValue(PROP_CUSTOM_BASE_PATH)
        }

        super.doOKAction()
    }

    companion object {
        private const val PROP_LAST_ADDRESS = "opencode.lastAddress"
        private const val PROP_LAST_PASSWORD = "opencode.lastPassword"
        private const val PROP_CUSTOM_BASE_PATH = "opencode.customBasePath"
        
        /**
         * Shows the dialog and returns the result.
         * @return ConnectionInfo if user clicked Connect, null if cancelled
         */
        internal fun getRememberedAddress(): ServerAddress? {
            return parseAddress(PropertiesComponent.getInstance().getValue(PROP_LAST_ADDRESS, ""))
        }

        private val SCHEME_PREFIX = Regex("^(https?)://", RegexOption.IGNORE_CASE)

        internal fun parseAddress(value: String?): ServerAddress? {
            val raw = value?.trim() ?: return null
            if (raw.isEmpty()) return null

            val scheme = SCHEME_PREFIX.find(raw)?.groupValues?.get(1)?.lowercase() ?: "http"
            val withoutScheme = raw.replaceFirst(SCHEME_PREFIX, "")
            val parts = withoutScheme.split(":")
            if (parts.size != 2) return null

            val hostname = parts[0].trim()
            val port = parts[1].trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (hostname.isBlank()) return null

            return ServerAddress(hostname, port, scheme)
        }

        fun show(project: Project, defaultAddress: String): ConnectionInfo? {
            val dialog = OpenCodeConnectDialog(project, defaultAddress)
            return if (dialog.showAndGet()) {
                ConnectionInfo(
                    dialog.hostname,
                    dialog.port,
                    dialog.password,
                    dialog.useWebInterface,
                    dialog.customBasePath,
                    dialog.scheme
                )
            } else {
                null
            }
        }
    }
}
