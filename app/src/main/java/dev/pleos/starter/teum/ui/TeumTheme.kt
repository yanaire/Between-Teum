package dev.pleos.starter.teum.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import dev.pleos.starter.R
import kotlin.math.roundToInt

/** Spacing scale (dp). Every margin/padding in the UI resolves to one of these. */
object TeumSpace {
    const val XXS = 2
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    const val XXXL = 48
}

/** Corner scale (dp). */
object TeumRadius {
    const val CARD = 20
    const val CONTROL = 14
    const val PANEL = 28
    const val PILL = 999
}

/** Control and layout sizes (dp). */
object TeumSize {
    const val TOUCH = 56
    const val PRIMARY = 64
    const val ICON = 24
    const val SITUATION_ICON = 32
    const val CONTENT_MAX = 1120
    const val WIDE_BREAK = 760
    const val GUTTER = 24
    const val CARD_PAD = 24
}

enum class TeumText { DISPLAY, TITLE, HEADING, SUBHEAD, BODY, LABEL, CAPTION, MICRO }

class TeumTheme(private val context: Context) {
    val canvas = context.getColor(R.color.teum_canvas)
    val surface = context.getColor(R.color.teum_surface)
    val surfaceAlt = context.getColor(R.color.teum_surface_alt)
    val ink = context.getColor(R.color.teum_ink)
    val inkMuted = context.getColor(R.color.teum_ink_muted)
    val line = context.getColor(R.color.teum_line)
    val pine = context.getColor(R.color.teum_pine)
    val pineDeep = context.getColor(R.color.teum_pine_deep)
    val pineSoft = context.getColor(R.color.teum_pine_soft)
    val clay = context.getColor(R.color.teum_clay)
    val claySoft = context.getColor(R.color.teum_clay_soft)
    val sky = context.getColor(R.color.teum_sky)
    val skySoft = context.getColor(R.color.teum_sky_soft)
    val white = context.getColor(R.color.teum_white)
    val disabled = context.getColor(R.color.teum_disabled)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    fun dpF(value: Int): Float = value * context.resources.displayMetrics.density

    fun alpha(color: Int, factor: Float): Int = Color.argb(
        (255 * factor.coerceIn(0f, 1f)).roundToInt(),
        Color.red(color), Color.green(color), Color.blue(color)
    )

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dpF(radiusDp)
            if (stroke != null && strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    fun pressable(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 0): Drawable {
        val content = rounded(fill, radiusDp, stroke, strokeDp)
        return RippleDrawable(ColorStateList.valueOf(alpha(ink, 0.12f)), content, null)
    }

    fun lp(
        width: Int, height: Int, weight: Float = 0f,
        left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    fun frameLp(
        width: Int, height: Int, gravity: Int = Gravity.TOP or Gravity.START,
        left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0
    ): android.widget.FrameLayout.LayoutParams = android.widget.FrameLayout.LayoutParams(width, height).apply {
        this.gravity = gravity
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    fun text(value: CharSequence, style: TeumText = TeumText.BODY, color: Int? = null): TextView =
        TextView(context).apply {
            text = value
            applyTextStyle(this, style, color ?: ink)
            includeFontPadding = false
            setLineSpacing(0f, 1.28f)
        }

    fun applyTextStyle(view: TextView, style: TeumText, color: Int = ink) {
        val size = when (style) {
            TeumText.DISPLAY -> 42f
            TeumText.TITLE -> 28f
            TeumText.HEADING -> 21f
            TeumText.SUBHEAD -> 18f
            TeumText.BODY -> 17f
            TeumText.LABEL -> 15f
            TeumText.CAPTION -> 14f
            TeumText.MICRO -> 13f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.setTextColor(color)
        view.typeface = when (style) {
            TeumText.DISPLAY, TeumText.TITLE, TeumText.HEADING ->
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            TeumText.LABEL, TeumText.SUBHEAD ->
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        if (style == TeumText.DISPLAY) view.letterSpacing = -0.01f
        view.setLineSpacing(0f, 1.28f)
    }

    fun sectionLabel(value: CharSequence): TextView = text(value, TeumText.LABEL, inkMuted).apply {
        letterSpacing = 0.04f
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun card(paddingDp: Int = TeumSize.CARD_PAD, fill: Int = surface, stroke: Int? = line): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(fill, TeumRadius.CARD, stroke, if (stroke == null) 0 else 1)
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, 0, TeumSpace.M)
        }

    fun clickableCard(tag: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = pressable(surface, TeumRadius.CARD, line, 1)
            setPadding(dp(TeumSize.CARD_PAD), dp(TeumSize.CARD_PAD), dp(TeumSize.CARD_PAD), dp(TeumSize.CARD_PAD))
            isClickable = true
            isFocusable = true
            this.tag = tag
            setOnClickListener { onClick() }
        }

    fun primaryButton(label: CharSequence, tag: String, onClick: () -> Unit): Button =
        button(label, tag, pine, white, null, 0, onClick)

    fun dangerButton(label: CharSequence, tag: String, onClick: () -> Unit): Button =
        button(label, tag, clay, white, null, 0, onClick)

    fun secondaryButton(label: CharSequence, tag: String, onClick: () -> Unit): Button =
        button(label, tag, surface, pine, pine, 1, onClick)

    private fun button(
        label: CharSequence, tag: String, fill: Int, fg: Int, stroke: Int?, strokeDp: Int,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        this.tag = tag
        stateListAnimator = null
        background = pressable(fill, TeumRadius.CONTROL, stroke, strokeDp)
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(TeumSize.TOUCH)
        minimumHeight = dp(TeumSize.TOUCH)
        minWidth = dp(132)
        gravity = Gravity.CENTER
        setPadding(dp(TeumSpace.XL), dp(TeumSpace.M), dp(TeumSpace.XL), dp(TeumSpace.M))
        includeFontPadding = false
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun quietButton(label: CharSequence, tag: String, color: Int = inkMuted, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            this.tag = tag
            stateListAnimator = null
            background = pressable(Color.TRANSPARENT, TeumRadius.CONTROL)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(TeumSize.TOUCH)
            minimumHeight = dp(TeumSize.TOUCH)
            minWidth = 0
            setPadding(dp(TeumSpace.M), dp(TeumSpace.S), dp(TeumSpace.M), dp(TeumSpace.S))
            includeFontPadding = false
            setOnClickListener { onClick() }
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun chip(label: CharSequence, tag: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(label, TeumText.LABEL, if (selected) pine else inkMuted).apply {
            this.tag = tag
            background = if (selected) rounded(pineSoft, TeumRadius.PILL)
            else rounded(surfaceAlt, TeumRadius.PILL)
            setPadding(dp(TeumSpace.L), dp(TeumSpace.S), dp(TeumSpace.L), dp(TeumSpace.S))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setOnClickListener { onClick() }
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, 0, TeumSpace.S, 0)
        }

    fun pill(label: CharSequence, fill: Int, fg: Int, tag: String? = null): TextView =
        text(label, TeumText.MICRO, fg).apply {
            if (tag != null) this.tag = tag
            background = rounded(fill, TeumRadius.PILL)
            setPadding(dp(TeumSpace.M), dp(TeumSpace.XS), dp(TeumSpace.M), dp(TeumSpace.XS))
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

    fun icon(resId: Int, desc: CharSequence?, tint: Int = pine, sizeDp: Int = TeumSize.ICON): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            setColorFilter(tint)
            contentDescription = desc
            importantForAccessibility =
                if (desc == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    fun progress(color: Int = pine): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(line)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }

    fun space(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    fun fullRow(vararg children: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            children.forEach { addView(it) }
        }

    fun weight(child: View, weight: Float, width: Int = 0): View {
        child.layoutParams = lp(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        return child
    }

    fun setEnabledLook(view: View, enabled: Boolean, enabledAlpha: Float = 1f, disabledAlpha: Float = 0.4f) {
        view.isEnabled = enabled
        view.alpha = if (enabled) enabledAlpha else disabledAlpha
    }

    fun checkBox(
        label: CharSequence, tag: String, checked: Boolean = false, onChanged: (Boolean) -> Unit
    ): CheckBox = CheckBox(context).apply {
        text = label
        this.tag = tag
        isChecked = checked
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        buttonTintList = ColorStateList.valueOf(pine)
        minHeight = dp(TeumSize.TOUCH)
        minimumHeight = dp(TeumSize.TOUCH)
        setPadding(0, dp(TeumSpace.S), 0, dp(TeumSpace.S))
        setLineSpacing(0f, 1.25f)
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, TeumSpace.XS, 0, 0)
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }

    fun editText(
        hint: CharSequence, tag: String, singleLine: Boolean = false, minLines: Int = 3
    ): EditText = EditText(context).apply {
        this.hint = hint
        this.tag = tag
        background = rounded(surfaceAlt, TeumRadius.CONTROL)
        setTextColor(ink)
        setHintTextColor(inkMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setPadding(dp(TeumSpace.L), dp(TeumSpace.M), dp(TeumSpace.L), dp(TeumSpace.M))
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        inputType = if (singleLine) {
            android.text.InputType.TYPE_CLASS_TEXT
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        setSingleLine(singleLine)
        if (!singleLine) {
            this.minLines = minLines
            maxLines = 6
        }
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun switchRow(
        label: CharSequence, tag: String, checked: Boolean, onChanged: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, TeumSpace.S, 0, TeumSpace.S)
        val labelView = text(label, TeumText.BODY, ink)
        labelView.layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(labelView)
        val toggle = Switch(context).apply {
            this.tag = tag
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(pine)
            trackTintList = ColorStateList.valueOf(alpha(pine, 0.45f))
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        addView(toggle, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun labeledRow(
        label: CharSequence, value: CharSequence, valueTag: String? = null, valueColor: Int = ink
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, 0, TeumSpace.XS, 0, TeumSpace.XS)
        val left = text(label, TeumText.CAPTION, inkMuted)
        left.layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(left)
        val right = text(value, TeumText.LABEL, valueColor)
        if (valueTag != null) right.tag = valueTag
        right.gravity = Gravity.END
        addView(right)
    }
}
