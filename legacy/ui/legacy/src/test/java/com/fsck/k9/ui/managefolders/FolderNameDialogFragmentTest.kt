package com.fsck.k9.ui.managefolders

import android.os.Bundle
import android.widget.Button
import androidx.fragment.app.testing.FragmentScenario.FragmentAction
import androidx.fragment.app.testing.launchFragment
import androidx.lifecycle.Lifecycle
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.ui.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import com.google.android.material.R as MaterialR

@RunWith(RobolectricTestRunner::class)
class FolderNameDialogFragmentTest {

    @Test
    fun `should return trimmed folder name when confirm button is clicked`() {
        val resultBundle = startDialogForResult { fragment ->
            fragment.folderNameEditText().setText("  Projects ")
            fragment.button(android.R.id.button1).performClick()
        }

        assertThat(resultBundle).isNotNull()
            .transform { it.getString(FolderNameDialogFragment.RESULT_FOLDER_NAME) }
            .isEqualTo("Projects")
    }

    @Test
    fun `should prefill initial name`() {
        var text: String? = null

        startDialogForResult(initialName = "Old name") { fragment ->
            text = fragment.folderNameEditText().text.toString()
        }

        assertThat(text).isEqualTo("Old name")
    }

    @Test
    fun `should show error and not return result when name contains the delimiter`() {
        var error: CharSequence? = null
        var isConfirmEnabled: Boolean? = null

        val resultBundle = startDialogForResult { fragment ->
            fragment.folderNameEditText().setText("a/b")
            ShadowLooper.idleMainLooper()
            error = fragment.requireDialog().findViewById<TextInputLayout>(R.id.folderNameLayout).error
            isConfirmEnabled = fragment.button(android.R.id.button1).isEnabled
            fragment.button(android.R.id.button1).performClick()
        }

        assertThat(error?.toString()).isEqualTo("The folder name can't contain \"/\".")
        assertThat(isConfirmEnabled).isNotNull().isFalse()
        assertThat(resultBundle).isNull()
    }

    @Test
    fun `should not return result when cancel button is clicked`() {
        val resultBundle = startDialogForResult { fragment ->
            fragment.folderNameEditText().setText("Projects")
            fragment.button(android.R.id.button2).performClick()
        }

        assertThat(resultBundle).isNull()
    }

    private fun startDialogForResult(
        initialName: String? = null,
        action: FragmentAction<FolderNameDialogFragment>,
    ): Bundle? {
        var resultBundle: Bundle? = null

        val arguments = FolderNameDialogFragment.createArguments(
            requestKey = REQUEST_KEY,
            title = "New folder",
            confirmText = "Create",
            folderPathDelimiter = "/",
            isTopLevel = true,
            initialName = initialName,
        )

        with(
            launchFragment<FolderNameDialogFragment>(
                fragmentArgs = arguments,
                themeResId = MaterialR.style.Theme_Material3_Light,
            ),
        ) {
            onFragment { fragment ->
                fragment.parentFragmentManager.setFragmentResultListener(REQUEST_KEY, fragment) { _, result ->
                    resultBundle = result
                }
            }
            moveToState(Lifecycle.State.RESUMED)

            onFragment(action)

            moveToState(Lifecycle.State.DESTROYED)
        }

        return resultBundle
    }

    private fun FolderNameDialogFragment.folderNameEditText(): TextInputEditText {
        return requireDialog().findViewById(R.id.folderName)
    }

    private fun FolderNameDialogFragment.button(id: Int): Button {
        return requireDialog().findViewById(id)
    }

    private companion object {
        const val REQUEST_KEY = "test_request"
    }
}
