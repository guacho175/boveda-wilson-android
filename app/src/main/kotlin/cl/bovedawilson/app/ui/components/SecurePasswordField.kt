package cl.bovedawilson.app.ui.components

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Arrays

private const val INITIAL_CAPACITY = 32
private const val MAX_PASSWORD_CHARS = 256

/**
 * Buffer borrable para un campo de contraseña. No es `data class`, no expone el contenido
 * como `String` y redacta [toString]. El `Editable` del control Android también se limpia
 * al consumir o abandonar la composición.
 */
class SecurePasswordState {
    private var chars = CharArray(INITIAL_CAPACITY)
    private var attachedField: EditText? = null
    private var revision by mutableIntStateOf(0)

    var length by mutableIntStateOf(0)
        private set

    val isNotEmpty: Boolean get() = length > 0

    internal fun attach(field: EditText) {
        attachedField = field
    }

    internal fun replaceFrom(source: CharSequence) {
        ensureCapacity(source.length)
        Arrays.fill(chars, '\u0000')
        for (index in source.indices) chars[index] = source[index]
        length = source.length
        revision++
    }

    fun contentEquals(other: SecurePasswordState): Boolean {
        revision
        other.revision
        if (length != other.length) return false
        var difference = 0
        for (index in 0 until length) {
            difference = difference or (chars[index].code xor other.chars[index].code)
        }
        return difference == 0
    }

    /** Transfiere una copia borrable al repositorio y limpia inmediatamente UI + buffer. */
    fun takeCharsAndClear(): CharArray {
        val result = chars.copyOf(length)
        clear()
        return result
    }

    fun clear() {
        Arrays.fill(chars, '\u0000')
        length = 0
        revision++
        attachedField?.text?.clear()
    }

    internal fun detach(field: EditText) {
        field.text?.clear()
        if (attachedField === field) attachedField = null
        Arrays.fill(chars, '\u0000')
        length = 0
        revision++
    }

    override fun toString(): String = "SecurePasswordState([REDACTED])"

    private fun ensureCapacity(required: Int) {
        if (required <= chars.size) return
        val replacement = CharArray(maxOf(required, chars.size * 2))
        chars.copyInto(replacement, endIndex = length)
        Arrays.fill(chars, '\u0000')
        chars = replacement
    }
}

@Composable
fun SecurePasswordField(
    label: String,
    state: SecurePasswordState,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val fieldRef = remember { arrayOfNulls<EditText>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                hint = label
                contentDescription = label
                isSingleLine = true
                isSaveEnabled = false
                setAutofillHints(null)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                transformationMethod = PasswordTransformationMethod.getInstance()
                filters = arrayOf(InputFilter.LengthFilter(MAX_PASSWORD_CHARS))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(text: Editable?) {
                        state.replaceFrom(text ?: "")
                    }
                })
                state.attach(this)
                fieldRef[0] = this
            }
        },
        update = { it.isEnabled = enabled }
    )

    DisposableEffect(state) {
        onDispose {
            fieldRef[0]?.let(state::detach) ?: state.clear()
            fieldRef[0] = null
        }
    }
}
