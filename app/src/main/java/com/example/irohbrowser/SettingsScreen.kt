package com.example.irohbrowser

import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

/** What the settings screen asks the activity to do. */
interface SettingsActions {
    fun open(index: Int)
    fun rename(index: Int, name: String)
    fun remove(index: Int)
    fun deletePasskey(credentialId: String)
    fun addEndpoint()
}

/**
 * The settings screen: every endpoint, its origin, and its passkeys.
 *
 * Draws a [SettingsModel] into [content] and turns taps into [SettingsActions],
 * asking first wherever the action cannot be undone. It keeps no state of its
 * own -- the activity renders it again after every change -- so what it shows
 * is never older than the last action.
 *
 * Buttons carry tags such as `remove:1` and `delete:<credential id>`, which is
 * how the tests find them.
 */
class SettingsScreen(private val content: LinearLayout, private val actions: SettingsActions) {

    private val context get() = content.context

    fun render(model: SettingsModel) {
        content.removeAllViews()
        content.addView(heading(R.string.settings_endpoints))
        model.endpoints.forEach { content.addView(endpointView(it)) }
        content.addView(button(R.string.settings_add_endpoint, "add") { actions.addEndpoint() })

        if (model.orphans.isNotEmpty()) {
            content.addView(heading(R.string.settings_orphans))
            content.addView(small(context.getString(R.string.settings_orphans_explained)))
            model.orphans.forEach { content.addView(passkeyView(it, it.rpId, showSite = true)) }
        }
    }

    private fun endpointView(row: EndpointRow): View = vertical().apply {
        setPadding(0, dp(16), 0, dp(8))
        val name = if (row.open) context.getString(R.string.settings_open_marker, row.name) else row.name
        addView(title(name))
        addView(
            small(row.origin ?: context.getString(R.string.settings_unreadable_ticket))
                .apply { typeface = Typeface.MONOSPACE },
        )
        addView(
            horizontal().apply {
                // On every row, the open one included: with a single endpoint
                // it is the only way back to the page that does not depend on
                // knowing about the system back button.
                addView(button(R.string.settings_open, "open:${row.index}") { actions.open(row.index) })
                addView(button(R.string.settings_rename, "rename:${row.index}") { askToRename(row) })
                addView(button(R.string.settings_remove, "remove:${row.index}") { askToRemove(row) })
            },
        )
        if (row.passkeys.isEmpty()) {
            addView(small(context.getString(R.string.settings_no_passkeys)))
        } else {
            row.passkeys.forEach { addView(passkeyView(it, row.name, showSite = false)) }
        }
    }

    private fun passkeyView(passkey: PasskeyRow, where: String, showSite: Boolean): View = horizontal().apply {
        setPadding(dp(8), dp(4), 0, dp(4))
        val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(passkey.created))
        val heading = if (showSite) {
            context.getString(R.string.passkey_on_site, passkey.account, passkey.rpId)
        } else {
            passkey.account
        }
        val details = context.getString(R.string.passkey_details, created, storage(passkey.storage))
        addView(
            vertical().apply {
                addView(body(heading))
                addView(small(details))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        addView(button(R.string.settings_delete, "delete:${passkey.credentialId}") { askToDelete(passkey, where) })
    }

    private fun storage(storage: KeyStorage?): String = context.getString(
        when (storage) {
            KeyStorage.StrongBox -> R.string.key_strongbox
            KeyStorage.TrustedEnvironment -> R.string.key_tee
            KeyStorage.Software -> R.string.key_software
            KeyStorage.Unknown -> R.string.key_unknown
            null -> R.string.key_missing
        },
    )

    private fun askToRename(row: EndpointRow) {
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(row.name)
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_rename_title)
            .setMessage(R.string.settings_rename_explained)
            .setView(field)
            .setPositiveButton(R.string.settings_save) { _, _ -> actions.rename(row.index, field.text.toString()) }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun askToRemove(row: EndpointRow) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.settings_remove_title, row.name))
            .setMessage(R.string.settings_remove_explained)
            .setPositiveButton(R.string.settings_remove) { _, _ -> actions.remove(row.index) }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun askToDelete(passkey: PasskeyRow, where: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.settings_delete_title, passkey.account))
            .setMessage(context.getString(R.string.settings_delete_explained, where))
            .setPositiveButton(R.string.settings_delete) { _, _ -> actions.deletePasskey(passkey.credentialId) }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun heading(label: Int): View =
        text(context.getString(label), MaterialR.style.TextAppearance_Material3_TitleLarge)
            .apply { setPadding(0, dp(16), 0, 0) }

    private fun title(value: String) = text(value, MaterialR.style.TextAppearance_Material3_TitleMedium)
    private fun body(value: String) = text(value, MaterialR.style.TextAppearance_Material3_BodyMedium)
    private fun small(value: String) = text(value, MaterialR.style.TextAppearance_Material3_BodySmall)

    private fun text(value: String, appearance: Int) = TextView(context).apply {
        text = value
        setTextAppearance(appearance)
    }

    private fun button(label: Int, tag: String, onClick: () -> Unit) =
        MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = context.getString(label)
            this.tag = tag
            setOnClickListener { onClick() }
        }

    private fun vertical() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun horizontal() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
