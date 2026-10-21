package com.t0msilver.voicebubble

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

/** A plain text field in another task, standing in for "any app" in the emulator smoke. */
class TestTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            id = R.id.test_field
            setText("Hello")
            setSelection(text.length)
            setPadding(48, 300, 48, 48)
        }
        setContentView(field)
        field.requestFocus()
    }
}
