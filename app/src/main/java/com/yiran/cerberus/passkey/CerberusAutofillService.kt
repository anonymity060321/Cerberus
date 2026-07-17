package com.yiran.cerberus.passkey

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.yiran.cerberus.R
import com.yiran.cerberus.util.SecurityUtil
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Autofill provider used both for password filling and for the HyperOS credential-provider bridge.
 *
 * Fill requests only receive a generic, authenticated entry. Account names, usernames and
 * passwords are loaded after the user authenticates in [AutofillAuthActivity].
 */
class CerberusAutofillService : AutofillService() {
    @Suppress("DEPRECATION")
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (
            cancellationSignal.isCanceled ||
            !SecurityUtil.isPasswordAutofillEnabled(this) ||
            !SecurityUtil.isMasterPasswordSet(this)
        ) {
            callback.onSuccess(null)
            return
        }

        val form = runCatching { parseForm(request) }.getOrNull()
        if (form == null || form.targetPackage == packageName) {
            callback.onSuccess(null)
            return
        }

        val authIntent = Intent(this, AutofillAuthActivity::class.java).apply {
            form.usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, it) }
            form.passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, it) }
            putExtra(AutofillAuthActivity.EXTRA_TARGET_LABEL, form.targetLabel)
            putExtra(AutofillAuthActivity.EXTRA_TARGET_KEY, form.targetKey)
            putExtra(AutofillAuthActivity.EXTRA_TARGET_PACKAGE, form.targetPackage)
        }
        val requestCode = nextRequestCode.incrementAndGet()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            authIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val menuPresentation = RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
            setTextViewText(android.R.id.text1, "Cerberus")
            setTextViewText(android.R.id.text2, "验证后选择账号")
        }

        val datasetBuilder = Dataset.Builder(menuPresentation)
            .setAuthentication(pendingIntent.intentSender)
            .setId("cerberus-auth-$requestCode")

        form.usernameId?.let { datasetBuilder.setValue(it, null) }
        form.passwordId?.let { datasetBuilder.setValue(it, null) }
        createInlinePresentation(request, pendingIntent)?.let {
            datasetBuilder.setInlinePresentation(it)
        }

        callback.onSuccess(
            FillResponse.Builder()
                .addDataset(datasetBuilder.build())
                .build()
        )
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Cerberus never captures credentials silently. New records are created inside the app.
        callback.onSuccess()
    }

    private fun createInlinePresentation(
        request: FillRequest,
        pendingIntent: PendingIntent
    ): InlinePresentation? {
        val spec = request.inlineSuggestionsRequest
            ?.inlinePresentationSpecs
            ?.firstOrNull()
            ?: return null

        val content = InlineSuggestionUi.newContentBuilder(pendingIntent)
            .setTitle("Cerberus")
            .setSubtitle("验证后填充")
            .setStartIcon(Icon.createWithResource(this, R.mipmap.cerberus))
            .setContentDescription("使用 Cerberus 填充")
            .build()

        return InlinePresentation(content.slice, spec, false)
    }

    private fun parseForm(request: FillRequest): ParsedForm? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val nodes = mutableListOf<AssistStructure.ViewNode>()
        repeat(structure.windowNodeCount) { windowIndex ->
            collectNodes(structure.getWindowNodeAt(windowIndex).rootViewNode, nodes)
        }

        val fillable = nodes.filter { node ->
            node.autofillId != null &&
                node.visibility == View.VISIBLE &&
                isTextInput(node)
        }
        if (fillable.isEmpty()) return null

        val passwordNodes = fillable.filter(::isPasswordField)
        if (passwordNodes.size > 1) return null
        val passwordNode = passwordNodes.singleOrNull()

        val explicitUsername = fillable.firstOrNull { node ->
            node !== passwordNode && isUsernameField(node)
        }
        val usernameNode = explicitUsername ?: passwordNode?.let { password ->
            val passwordIndex = fillable.indexOf(password)
            fillable.take(passwordIndex).lastOrNull { !isPasswordField(it) }
        }

        if (usernameNode == null && passwordNode == null) return null

        val webDomain = nodes.firstNotNullOfOrNull { node ->
            node.webDomain?.trim()?.takeIf(String::isNotEmpty)
        }?.lowercase(Locale.ROOT)
        val targetPackage = structure.activityComponent?.packageName ?: return null
        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("当前应用")
        val safeLabel = appLabel
            .filter { !it.isISOControl() }
            .trim()
            .take(80)
            .ifEmpty { "当前应用" }

        return ParsedForm(
            usernameId = usernameNode?.autofillId,
            passwordId = passwordNode?.autofillId,
            targetPackage = targetPackage,
            targetLabel = webDomain?.let { "网站 $it" } ?: safeLabel,
            targetKey = webDomain?.let { "web:$it@app:$targetPackage" }
                ?: "app:$targetPackage"
        )
    }

    private fun collectNodes(
        node: AssistStructure.ViewNode,
        destination: MutableList<AssistStructure.ViewNode>
    ) {
        destination += node
        repeat(node.childCount) { childIndex ->
            collectNodes(node.getChildAt(childIndex), destination)
        }
    }

    private fun isTextInput(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillType == View.AUTOFILL_TYPE_TEXT) return true
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        if (
            inputClass == InputType.TYPE_CLASS_TEXT ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_NUMBER
        ) {
            return true
        }
        return node.className?.toString()?.contains("EditText", ignoreCase = true) == true
    }

    private fun isPasswordField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints.orEmpty().map { it.lowercase(Locale.ROOT) }
        if (hints.any { it == "newpassword" }) return false
        if (hints.any { it == "password" }) return true

        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        if (
            inputClass == InputType.TYPE_CLASS_TEXT &&
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        ) {
            return true
        }
        if (
            inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }

        val semantic = semanticText(node)
        if (
            semantic.contains("newpassword") ||
            semantic.contains("new-password") ||
            semantic.contains("confirm") ||
            semantic.contains("确认密码")
        ) {
            return false
        }
        return listOf("password", "passwd", "pwd", "密码").any(semantic::contains)
    }

    private fun isUsernameField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints.orEmpty().map { it.lowercase(Locale.ROOT) }
        if (
            hints.any {
                it in setOf("username", "emailaddress", "phone", "phonenumber")
            }
        ) {
            return true
        }
        val semantic = semanticText(node)
        return listOf(
            "username",
            "user-name",
            "login",
            "account",
            "email",
            "phone",
            "mobile",
            "账号",
            "用户名",
            "手机号"
        ).any(semantic::contains)
    }

    private fun semanticText(node: AssistStructure.ViewNode): String {
        val values = mutableListOf<String>()
        node.autofillHints.orEmpty().forEach(values::add)
        node.idEntry?.let(values::add)
        node.hint?.toString()?.let(values::add)
        node.className?.toString()?.let(values::add)
        node.htmlInfo?.attributes?.forEach { attribute ->
            values += attribute.first
            values += attribute.second
        }
        return values.joinToString(" ").lowercase(Locale.ROOT)
    }

    private data class ParsedForm(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val targetPackage: String,
        val targetLabel: String,
        val targetKey: String
    )

    private companion object {
        val nextRequestCode = AtomicInteger(1000)
    }
}
