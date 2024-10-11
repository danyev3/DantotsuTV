package ani.dantotsu.others

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetCustomBinding
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

open class CustomBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCustomBinding? = null
    private val binding get() = _binding!!

    private var onDismissListener: (() -> Unit)? = null

    private val viewList = mutableListOf<View>()
    fun addView(view: View) {
        viewList.add(view)
    }

    var title: String? = null
    fun setTitleText(string: String) {
        title = string
    }

    private var checkText: String? = null
    private var checkChecked: Boolean = false
    private var checkCallback: ((Boolean) -> Unit)? = null
    fun setCheck(text: String, checked: Boolean, callback: ((Boolean) -> Unit)) {
        checkText = text
        checkChecked = checked
        checkCallback = callback
    }

    private var textInputHint: String? = null
    private var textInputType: Int? = null
    private var textInputMaxLength: Int? = null
    private var textPrecompiled: String? = null
    private var textEditable: Boolean = true
    fun setTextInput(
        hint: String? = null,
        type: Int = 1,
        maxLength: Int? = null,
        precompiledText: String? = null,
        isEditable: Boolean = true
    ) {
        textInputHint = hint
        textInputType = type
        textInputMaxLength = maxLength
        textPrecompiled = precompiledText
        textEditable = isEditable
    }

    private var negativeText: String? = null
    private var negativeCallback: (() -> Unit)? = null
    fun setNegativeButton(text: String, callback: (() -> Unit)) {
        negativeText = text
        negativeCallback = callback
    }

    private var positiveText: String? = null
    private var positiveCallback: (() -> Unit)? = null
    fun setPositiveButton(text: String, callback: (() -> Unit)) {
        positiveText = text
        positiveCallback = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetCustomBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.bottomSheerCustomTitle.text = title
        viewList.forEach {
            binding.bottomDialogCustomContainer.addView(it)
        }
        if (checkText != null) binding.bottomDialogCustomCheckBox.apply {
            visibility = View.VISIBLE
            text = checkText
            isChecked = checkChecked
            setOnCheckedChangeListener { _, checked ->
                checkCallback?.invoke(checked)
            }
        }

        if (textInputHint != null || textPrecompiled != null) binding.bottomDialogCustomTextInput.apply {
            visibility = View.VISIBLE
            hint = textInputHint
            // 1 = text, 2 = number, 3 = phone, 4 = password
            textInputType?.let { inputType = it }
            if (textInputMaxLength != null) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(textInputMaxLength!!))
            }
            if (textPrecompiled != null) setText(textPrecompiled)
            isFocusable = textEditable
            isFocusableInTouchMode = textEditable
            isClickable = textEditable
        }

        if (negativeText != null) binding.bottomDialogCustomNegative.apply {
            visibility = View.VISIBLE
            text = negativeText
            setOnClickListener {
                negativeCallback?.invoke()
            }
        }

        if (positiveText != null) binding.bottomDialogCustomPositive.apply {
            visibility = View.VISIBLE
            text = positiveText
            setOnClickListener {
                positiveCallback?.invoke()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Focus the text input if conditions are met
        if ((textInputHint != null || textPrecompiled != null) && textEditable) {
            binding.bottomDialogCustomTextInput.requestFocus()
            dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    fun addOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = CustomBottomDialog()
    }
}
