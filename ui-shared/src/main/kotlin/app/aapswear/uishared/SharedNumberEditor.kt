package app.aapswear.uishared

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText

/** Compact direct-value entry shared by classic Mobile and Wear settings sliders. */
object SharedNumberEditor {
    fun show(activity: Activity, title: String, current: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current.toString())
            setSelection(length())
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Übernehmen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value !in min..max) {
                    input.error = "$min bis $max"
                } else {
                    onValue(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }
}
