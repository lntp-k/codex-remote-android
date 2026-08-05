package com.codex.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketException

class ConnectionErrorMessageTest {
    @Test
    fun socketAbortIsPresentedAsAnActionableKoreanMessage() {
        val result = mapError(SocketException("Software caused connection abort"))

        assertEquals(
            "SSH 연결이 중단되었습니다. 휴대폰 네트워크와 중계 서버 연결을 확인하고 다시 시도하세요.",
            result,
        )
    }

    @Test
    fun wrappedSocketAbortStillUsesTheActionableMessage() {
        val result = mapError(
            RuntimeException(
                "SSH bootstrap failed",
                SocketException("Software caused connection abort"),
            ),
        )

        assertEquals(
            "SSH 연결이 중단되었습니다. 휴대폰 네트워크와 중계 서버 연결을 확인하고 다시 시도하세요.",
            result,
        )
    }

    private fun mapError(error: Throwable): Any? {
        return friendlyError(error)
    }
}
