package com.fsck.k9.storage.messages

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class RemoveGmailPrefixTest {
    @Test
    fun `should remove Gmail prefix`() {
        assertThat(removeGmailPrefix("[Gmail]/Sent Mail")).isEqualTo("Sent Mail")
    }

    @Test
    fun `should remove Google Mail prefix`() {
        assertThat(removeGmailPrefix("[Google Mail]/Drafts")).isEqualTo("Drafts")
    }

    @Test
    fun `should leave other names unchanged`() {
        assertThat(removeGmailPrefix("Work/[Gmail]")).isEqualTo("Work/[Gmail]")
        assertThat(removeGmailPrefix("Gmail/Sent")).isEqualTo("Gmail/Sent")
    }
}
