package com.fsck.k9.ui.managefolders

import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import kotlin.test.Test

class FolderOperationUiTest {
    @Test
    fun `sanitizeServerMessage() should replace control and format characters`() {
        // Arrange
        val serverMessage = " Permission\r\ndenied\u0000 for‮ folder\t "

        // Act
        val result = sanitizeServerMessage(serverMessage)

        // Assert
        assertThat(result).isEqualTo("Permission denied for folder")
    }

    @Test
    fun `sanitizeServerMessage() should limit length`() {
        // Arrange
        val serverMessage = "a".repeat(1000)

        // Act
        val result = sanitizeServerMessage(serverMessage)

        // Assert
        assertThat(result).hasLength(200)
    }
}
