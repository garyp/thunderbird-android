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
import net.thunderbird.feature.mail.folder.api.FolderNameError
import net.thunderbird.feature.mail.folder.api.FolderNameValidator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import com.google.android.material.R as MaterialR

@RunWith(RobolectricTestRunner::class)
class FolderNameDialogFragmentTest {

    @Before
    fun setUp() {
        startKoin {
            modules(
                module {
                    single<FolderNameValidator> { FakeFolderNameValidator() }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `should return trimmed folder name when confirm button is clicked`() {
        // Arrange & Act
        val resultBundle = startDialogForResult { testSubject ->
            testSubject.folderNameEditText().setText("  Projects ")
            testSubject.button(android.R.id.button1).performClick()
        }

        // Assert
        assertThat(resultBundle).isNotNull()
            .transform { it.getString(FolderNameDialogFragment.RESULT_FOLDER_NAME) }
            .isEqualTo("Projects")
    }

    @Test
    fun `should prefill initial name`() {
        // Arrange
        var text: String? = null

        // Act
        startDialogForResult(initialName = "Old name") { testSubject ->
            text = testSubject.folderNameEditText().text.toString()
        }

        // Assert
        assertThat(text).isEqualTo("Old name")
    }

    @Test
    fun `should show error and not return result when name contains the delimiter`() {
        // Arrange
        var error: CharSequence? = null
        var isConfirmEnabled: Boolean? = null

        // Act
        val resultBundle = startDialogForResult { testSubject ->
            testSubject.folderNameEditText().setText("a/b")
            ShadowLooper.idleMainLooper()
            error = testSubject.requireDialog().findViewById<TextInputLayout>(R.id.folderNameLayout).error
            isConfirmEnabled = testSubject.button(android.R.id.button1).isEnabled
            testSubject.button(android.R.id.button1).performClick()
        }

        // Assert
        assertThat(error?.toString()).isEqualTo("The folder name can't contain \"/\".")
        assertThat(isConfirmEnabled).isNotNull().isFalse()
        assertThat(resultBundle).isNull()
    }

    @Test
    fun `should not return result when cancel button is clicked`() {
        // Arrange & Act
        val resultBundle = startDialogForResult { testSubject ->
            testSubject.folderNameEditText().setText("Projects")
            testSubject.button(android.R.id.button2).performClick()
        }

        // Assert
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

    private class FakeFolderNameValidator : FolderNameValidator {
        override fun validate(name: String, folderPathDelimiter: String, isTopLevel: Boolean): FolderNameError? {
            return when {
                name.isBlank() -> FolderNameError.EMPTY
                folderPathDelimiter in name -> FolderNameError.CONTAINS_DELIMITER
                else -> null
            }
        }
    }

    private companion object {
        const val REQUEST_KEY = "test_request"
    }
}
